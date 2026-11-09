package com.itda.backend.service.record;

import java.io.IOException;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.record.RawRecordStatus;
import com.itda.backend.exception.agent.AgentQueueFullException;
import com.itda.backend.exception.record.RawRecordEntryLimitException;
import com.itda.backend.exception.record.RawRecordNotFoundException;
import com.itda.backend.exception.record.RawRecordStorageException;
import com.itda.backend.exception.record.RawRecordValidationException;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.record.RawRecordRepository;
import com.itda.backend.service.agent.AgentQueueGuard;
import com.itda.backend.service.storage.RawFileStorage;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class RawRecordService {

    // ponytail: 매직바이트 스니핑 없이 확장자/선언된 content-type 이중 화이트리스트로만 검증.
    // 팀 합의로 파일 종류가 늘어나면 이 목록만 넓히면 됨.
    // jpg/jpeg/png/hwp는 이번 학기 범위 밖으로 제외(노션 "파일 형식 결정" 문서, 2026-09-29) —
    // jpg/png는 OCR 필요, hwp는 자바 파싱이 매우 어려움. docx는 #383에서 추가.
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("csv", "txt", "pdf", "docx");

    // application/vnd.ms-excel: Windows가 .csv를 저장할 때 흔히 이 Content-Type으로 보낸다
    // (#113, 최재혁님 리뷰) — 실제로 정상 csv 파일이 이 값 때문에 거부됐었다.
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "text/csv", "application/vnd.ms-excel", "text/plain", "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    // "8월21일" · "2026년 8월 21일" — 한글 표기는 뜻이 분명해서 가장 먼저 본다.
    private static final Pattern FILENAME_DATE_KOREAN =
            Pattern.compile("(?:(?<year>20\\d{2})\\s*년\\s*)?(?<month>\\d{1,2})\\s*월\\s*(?<day>\\d{1,2})\\s*일");

    // "2026-08-21" · "2026.08.21" · "20260821" — 네 자리 연도가 있으면 그대로 믿는다.
    private static final Pattern FILENAME_DATE_WITH_YEAR =
            Pattern.compile("(?<year>20\\d{2})[-._]?(?<month>\\d{1,2})[-._]?(?<day>\\d{1,2})");

    // "0821_관찰일지.txt" 의 MMDD. 앞뒤에 다른 숫자가 붙어 있으면(전화번호·학번 등) 보지 않는다.
    private static final Pattern FILENAME_DATE_MONTH_DAY =
            Pattern.compile("(?<!\\d)(?<month>\\d{2})(?<day>\\d{2})(?!\\d)");

    private final RawRecordRepository rawRecordRepository;
    private final RawRecordRecorder rawRecordRecorder;
    private final RawFileStorage rawFileStorage;
    private final UserService userService;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalEntrySplitter journalEntrySplitter;
    private final ChildRepository childRepository;
    private final RawTextExtractor rawTextExtractor;
    private final RawRecordUploadGate uploadGate;
    private final AgentQueueGuard agentQueueGuard;
    private final RawRecordProperties properties;

    /**
     * 인증된 사용자의 소속 기관을 찾는다.
     *
     * <p>예전에는 컨트롤러가 받은 값(실제로는 카카오 회원번호)을 그대로 기관 식별자로 썼다.
     * 이제 principal 은 내부 userId 이므로, 여기서 실제 소속 기관으로 바꾼다.
     * 기관 소속이 아닌 사용자(보호자 등)는 여기서 막힌다.
     *
     * <p>RawRecord.institutionId 는 문자열 컬럼이라 그대로 문자열로 넘긴다.
     * 컬럼 타입을 Long 으로 바꾸는 일은 마이그레이션 도구가 들어온 뒤에 한다 —
     * ddl-auto: update 는 이미 만들어진 컬럼의 타입을 바꿔주지 않는다.
     */
    private String resolveInstitutionId(String userId) {
        return String.valueOf(userService.getOrganizationIdOf(userId));
    }

    /**
     * 검사를 통과한 파일 이름 · 확장자 · 형식 (#450). 한 번에 올리는 업로드와 저장소로 바로 올리는 업로드가 같은 기준을 쓴다.
     *
     * @param displayName 경로를 떼어 낸 화면용 이름
     * @param extension   소문자 확장자. 허용 목록 안이다
     */
    public record ValidatedFile(String displayName, String extension, String contentType) {
    }

    /** 바이트를 읽는 방법. 업로드 자리를 잡은 뒤에 부른다. */
    @FunctionalInterface
    public interface BytesSource {
        byte[] read() throws IOException;
    }

    /**
     * 파일 이름 · 확장자 · 형식을 지금 업로드와 같은 기준으로 본다. 매직바이트는 보지 않는다(위 허용 목록 주석).
     *
     * @throws RawRecordValidationException 이름이 너무 길거나 확장자 · 형식이 허용되지 않는다
     */
    public ValidatedFile validate(String originalFilename, String contentType) {
        String displayFilename = sanitizeDisplayName(originalFilename);
        if (displayFilename.length() > RawRecord.MAX_TEXT_FIELD_LENGTH) {
            throw new RawRecordValidationException("file name is too long");
        }

        String extension = extractExtension(displayFilename);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new RawRecordValidationException("unsupported file extension");
        }

        // Office 가 없는 PC·일부 모바일 브라우저는 docx 를 application/octet-stream 으로 보낸다(#113 과 같은 종류).
        boolean docxAsOctetStream = "docx".equals(extension) && "application/octet-stream".equals(contentType);
        if (contentType == null || !(ALLOWED_CONTENT_TYPES.contains(contentType) || docxAsOctetStream)) {
            throw new RawRecordValidationException("unsupported content type");
        }
        return new ValidatedFile(displayFilename, extension, contentType);
    }

    /**
     * 바이트를 읽어 일지로 나누고 상한을 본다. 바이트 읽기 · 글자 뽑기 · 나누기는 서버 전체에서 동시에 몇 개까지만 한다 (#424) —
     * 파일마다 최대 20MB 를 힙에 올리고 docx 는 그보다 크게 풀린다. 바이트 배열은 이 블록 밖에서 쓰지 않는다.
     *
     * @param bytes {@code null} 을 돌려주면 글자를 못 뽑은 파일처럼 빈 목록이다. 읽기 오류를 던지면 저장 실패(500)다
     * @throws RawRecordUploadBusyException 자리와 기다리는 줄이 꽉 찼다
     * @throws RawRecordEntryLimitException 일지가 너무 많거나 하나가 너무 길다
     */
    public List<JournalEntrySplitter.SplitEntry> splitWithinLimits(String extension, BytesSource bytes) {
        List<JournalEntrySplitter.SplitEntry> entries;
        try (RawRecordUploadGate.Slot slot = uploadGate.acquire()) {
            entries = splitIntoEntries(extension, bytes.read());
        } catch (IOException e) {
            throw new RawRecordStorageException("failed to read raw file", e);
        }
        requireWithinEntryLimits(entries);
        return entries;
    }

    /**
     * 받을 원본 기록. 처리 대기 상태이고, 파일 이름에 명부 아동 이름이 있으면 힌트를 채운다. 아직 저장하지 않는다.
     */
    public RawRecord draft(Long organizationId, ValidatedFile file, String storedPath, long sizeBytes) {
        RawRecord rawRecord = new RawRecord(
                String.valueOf(organizationId),
                file.displayName(),
                storedPath,
                file.contentType(),
                sizeBytes,
                RawRecordStatus.PENDING);
        applyHintFromFilename(rawRecord, organizationId, file.displayName());
        return rawRecord;
    }

    /** 날짜를 확정하지 못한 일지에 같은 파일의 첫 날짜 → 파일명 → 업로드 날짜 순으로 날짜를 채운다. */
    public List<JournalEntrySplitter.SplitEntry> dated(
            RawRecord rawRecord, List<JournalEntrySplitter.SplitEntry> entries) {
        return withEntryDates(rawRecord, entries);
    }

    public RawRecord ingest(String userId, MultipartFile file) {
        // 기관 식별자는 이제 사용자의 소속 기관 id(숫자)라, 비어 있거나 컬럼 길이를 넘을 수 없다.
        // 예전에 있던 두 검사는 클라이언트가 보낸 값을 믿던 시절의 것이라 지웠다.
        Long organizationId = userService.getOrganizationIdOf(userId);
        String institutionId = String.valueOf(organizationId);
        if (file == null || file.isEmpty()) {
            throw new RawRecordValidationException("file is required");
        }

        ValidatedFile validated = validate(file.getOriginalFilename(), file.getContentType());
        String displayFilename = validated.displayName();
        String extension = validated.extension();
        String contentType = validated.contentType();

        List<JournalEntrySplitter.SplitEntry> entries =
                splitWithinLimits(extension, () -> readBytes(file, institutionId));
        // 흔한 경우는 저장소에 올리기 전에 거절한다. 잠그지 않고 센 값이라 상한을 지키지는 않는다 — 아래 accept 가 다시 센다.
        agentQueueGuard.peek(organizationId, entries.size());

        String storedPath;
        try {
            storedPath = rawFileStorage.store(file, extension);
        } catch (IOException | RuntimeException e) {
            // RuntimeException도 함께 잡는다 — S3RawFileStorage.store()는 인증/네트워크 오류 시
            // IOException이 아니라 SdkException(unchecked)을 던질 수 있어서, IOException만 잡으면
            // 이 예외가 그대로 새어나가 RAW_RECORD_STORAGE_FAILED 대신 일반 서버 오류로 응답된다.
            log.warn("raw file storage failed for institutionId={}", institutionId);
            throw new RawRecordStorageException("failed to store raw file", e);
        }

        RawRecord rawRecord = draft(organizationId, validated, storedPath, file.getSize());

        RawRecord saved;
        try {
            saved = rawRecordRecorder.accept(rawRecord, organizationId, withEntryDates(rawRecord, entries));
        } catch (AgentQueueFullException e) {
            // 그사이 같은 기관의 다른 업로드가 자리를 채웠다. 받지 않은 파일이라 append-only 대상이 아니다 — 지운다.
            rawFileStorage.delete(storedPath);
            throw e;
        } catch (RuntimeException e) {
            // 원본은 append-only — DB 저장이 실패해도 이미 저장소에 올라간 파일은 지우지 않는다.
            // 대신 FAILED 상태로 별도 기록을 남겨서, 나중에 추적/재처리할 수 있게 한다. 원본 행과 일지는 한 트랜잭션이라
            // 일지 저장이 실패해도 여기로 온다(#424) — 일지 0건으로 성공시키던 예전과 다르다.
            log.error("failed to persist raw record and journal entries for storedPath={}; raw file is kept", storedPath, e);
            try {
                rawRecordRecorder.recordFailure(institutionId, displayFilename, storedPath, contentType, file.getSize());
            } catch (RuntimeException retryFailure) {
                log.error("failed to record FAILED status for storedPath={}; "
                        + "raw file remains untracked in DB but preserved in storage", storedPath, retryFailure);
            }
            throw new RawRecordStorageException("failed to persist raw record metadata", e);
        }

        log.info("raw record intake recorded id={} institutionId={} status={} journalEntries={}",
                saved.getId(), institutionId, saved.getStatus(), entries.size());
        return saved;
    }

    /**
     * 업로드 본문을 읽는다. 못 읽으면 분리만 건너뛴다 — 저장소 저장이 곧이어 따로 읽으므로, 정말 스트림이 깨졌다면 거기서
     * 실패해 RawRecordStorageException 으로 끝난다.
     *
     * <p>rawFileStorage.store() 보다 먼저 읽는다 — MultipartFile.transferTo() 는 구현에 따라 파일을 "이동"시킬 수 있어서
     * (Spring Javadoc) store() 뒤에 다시 읽으면 실패할 수 있다.
     */
    private static byte[] readBytes(MultipartFile file, String institutionId) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            log.warn("failed to pre-read raw file bytes for splitting institutionId={}", institutionId, e);
            return null;
        }
    }

    /**
     * 업로드된 파일을 기록(JournalEntry) 단위로 쪼갠다. 저장보다 먼저 한다 (#424) — 상한을 넘는 파일은 저장하지 않고 거절해야
     * 해서다. 글자를 못 뽑은 파일(깨진 docx, 사진만 있는 pdf, 압축이 너무 크게 풀리는 docx)이나 사전 읽기가 실패한 경우는 빈
     * 목록이다 — 업로드는 그대로 하고 entries 가 비어 있는 게 정상이다(O-26).
     *
     * <p>워커가 아니라 요청 안에서 동기로 처리한다 — AI 호출(매칭)은 여기서 하지 않는다,
     * 그건 별도 워커가 PENDING 상태의 JournalEntry를 긁어가서 한다(노션 "기록 분리 기능" 문서).
     */
    private List<JournalEntrySplitter.SplitEntry> splitIntoEntries(String extension, byte[] fileBytes) {
        Optional<String> text = rawTextExtractor.extract(extension, fileBytes);
        if (text.isEmpty()) {
            return List.of();
        }
        // 상한을 넘는 순간 멈춘다 — 넘는 파일은 어차피 거절하고, 끝까지 나누면 날짜 줄이 아주 많은 파일 하나가 힙을 크게 쓴다.
        // 한 건 더 여유를 둔다: 문서 머리말(첫 날짜 앞 한 건)을 빼고도 상한을 넘은 것이 보여야 잘린 채 받지 않는다.
        return withoutDocumentPreamble(
                extension, journalEntrySplitter.split(
                        "pdf".equals(extension) ? journalEntrySplitter.joinWrappedLines(text.get()) : text.get(),
                        properties.limits().maxEntriesPerFile() + 1));
    }

    /**
     * 일지가 너무 많거나 하나가 너무 긴 파일은 받지 않는다 (#424). 일지마다 매칭 · 검증 AI 를 부르므로, 파일 하나로 일지를 수천 건
     * 만들거나 한 건에 아주 긴 글을 실어 보내는 일을 막는다. 잘라서 받지 않는다 — 기록이 선생님 모르게 사라진다.
     */
    private void requireWithinEntryLimits(List<JournalEntrySplitter.SplitEntry> entries) {
        int maxEntries = properties.limits().maxEntriesPerFile();
        if (entries.size() > maxEntries) {
            throw new RawRecordEntryLimitException(
                    "too many journal entries in file: " + entries.size() + " > " + maxEntries);
        }
        for (JournalEntrySplitter.SplitEntry entry : entries) {
            if (entry.content().length() > JournalEntry.MAX_CONTENT_LENGTH) {
                throw new RawRecordEntryLimitException("journal entry too long: " + entry.content().length()
                        + " > " + JournalEntry.MAX_CONTENT_LENGTH);
            }
        }
    }

    /** 날짜를 확정하지 못한 일지에 같은 파일의 첫 날짜 → 파일명 → 업로드 날짜 순으로 날짜를 채운다 ({@link #resolveFallbackDate}). */
    private List<JournalEntrySplitter.SplitEntry> withEntryDates(
            RawRecord rawRecord, List<JournalEntrySplitter.SplitEntry> entries) {
        if (entries.isEmpty()) {
            return entries;
        }
        LocalDate fallbackDate = resolveFallbackDate(rawRecord, entries);
        return entries.stream()
                .map(entry -> entry.entryDate() != null
                        ? entry
                        : new JournalEntrySplitter.SplitEntry(fallbackDate, entry.content()))
                .toList();
    }

    /**
     * docx·pdf 는 첫 날짜 앞의 머리말을 기록으로 만들지 않는다 (#383).
     *
     * <p>문서 형식 일지는 맨 위에 제목과 아동 이름·생년월일 같은 머리말이 있다. 이걸 날짜 없는 기록으로
     * 만들면 검증이 개인정보로 막고, 선생님이 수정 요청 큐에서 처리해야 한다. 아동 이름·생년월일은 이미
     * 파일명 힌트로 매칭에 쓰고, 원본 파일에는 그대로 남아 있으니 기록으로 만들 필요가 없다.
     *
     * <p>날짜 헤더가 하나도 없는 문서는 그대로 둔다 — 그때는 전체가 한 건이라 버리면 기록이 사라진다.
     * txt·csv 는 지금처럼 머리말도 첫 날짜를 받아 기록으로 남긴다(코드리뷰 반영 동작).
     */
    private static List<JournalEntrySplitter.SplitEntry> withoutDocumentPreamble(
            String extension, List<JournalEntrySplitter.SplitEntry> entries) {
        boolean hasDatedEntry = entries.stream().anyMatch(entry -> entry.entryDate() != null);
        if (!RawTextExtractor.DOCUMENT_EXTENSIONS.contains(extension) || !hasDatedEntry) {
            return entries;
        }
        // 날짜 헤더가 있는 파일에서 날짜 없는 기록은 첫 헤더 앞의 머리말뿐이다. 내용은 남기지 않고 건수만 남긴다.
        List<JournalEntrySplitter.SplitEntry> dated =
                entries.stream().filter(entry -> entry.entryDate() != null).toList();
        if (dated.size() < entries.size()) {
            log.info("dropped {} preamble block(s) before the first date in a {} file", entries.size() - dated.size(), extension);
        }
        return dated;
    }

    /**
     * 날짜를 확정하지 못한 기록에 넣을 날짜를 정한다. 같은 파일의 첫 날짜 → 파일명 → 업로드 날짜 순이다.
     *
     * <p>요약은 아동 × 날짜 × 기관으로 묶이므로(DB 스키마 §8.2) {@code entry_date} 가 비면 그 기록은
     * 어느 묶음에도 들어가지 못하고 요약에서 통째로 빠진다. 추정한 날짜가 하루이틀 어긋나는 것보다
     * 기록이 아예 사라지는 쪽이 나쁘다고 보고 채운다.
     *
     * <p><b>같은 파일의 첫 날짜를 가장 먼저 보는 이유</b> — 날짜 헤더 앞에 표지 줄이 있는 파일
     * ({@code 표지: 8월 관찰일지 / 9/15 … / 9/16 …})에서 표지만 파일명 날짜를 받으면 한 파일이
     * 서로 다른 날짜 묶음으로 흩어진다. 파일이 스스로 밝힌 날짜가 파일명보다 믿을 만하다.
     *
     * <p><b>알려진 한계</b> — 추정값인지 본문에서 읽은 값인지 구분해 두는 컬럼이 없다. 추정이
     * 틀리면 그 기록이 엉뚱한 날짜 묶음에 들어가는데, 교사가 Gate 1 에서 요약을 볼 때 날짜가 안 맞는
     * 내용이 섞인 것으로 알아챌 수는 있다. 구분이 필요하다고 팀이 판단하면 그때 컬럼을 추가한다.
     */
    private LocalDate resolveFallbackDate(RawRecord rawRecord, List<JournalEntrySplitter.SplitEntry> entries) {
        LocalDate firstDateInFile = entries.stream()
                .map(JournalEntrySplitter.SplitEntry::entryDate)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (firstDateInFile != null) {
            return firstDateInFile;
        }

        // 저장 전이지만 만든 시각은 생성자에서 정해진다 — 저장된 created_at 과 같다.
        LocalDate uploadedOn = rawRecord.getCreatedAt().toLocalDate();
        return parseDateFromFilename(rawRecord.getOriginalFilename(), uploadedOn).orElse(uploadedOn);
    }

    /**
     * 파일명에서 기록 날짜를 뽑는다. 뜻이 분명한 형식부터 본다 — 한글 표기 → 네 자리 연도 → {@code MMDD}.
     *
     * <p>연도가 없는 형식({@code 8월21일} · {@code 0821})은 업로드 연도로 둔다. 숫자 네 자리는
     * 날짜가 아닐 수도 있어서({@code 학생1234.txt}) 실제 달·일로 성립할 때만 받는다 — 성립하지
     * 않으면 비우고 호출한 쪽이 업로드 날짜로 내려간다.
     *
     * <p><b>알려진 한계</b> — {@code MMDD} 는 버전 번호나 기관 코드와 구분할 수 없다.
     * {@code 일지_v2_1130.txt} 를 11월 30일로, {@code 센터코드1203_일지.txt} 를 12월 3일로 읽는다
     * (코드리뷰로 확인). 실제 기관 파일명 표본이 모이기 전에는 어느 쪽 오차가 더 나쁜지 판단할
     * 근거가 없어서, 지금은 업로드 날짜로 내려가는 것보다 낫다고 보고 그대로 둔다.
     */
    private static Optional<LocalDate> parseDateFromFilename(String filename, LocalDate uploadedOn) {
        for (Pattern format : List.of(FILENAME_DATE_KOREAN, FILENAME_DATE_WITH_YEAR, FILENAME_DATE_MONTH_DAY)) {
            Matcher matcher = format.matcher(filename);
            while (matcher.find()) {
                String year = matcher.namedGroups().containsKey("year") ? matcher.group("year") : null;
                Optional<LocalDate> parsed = toDate(year == null ? uploadedOn.getYear() : Integer.parseInt(year),
                        Integer.parseInt(matcher.group("month")), Integer.parseInt(matcher.group("day")));
                if (parsed.isPresent()) {
                    return parsed;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<LocalDate> toDate(int year, int month, int day) {
        try {
            return Optional.of(LocalDate.of(year, month, day));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    /**
     * 파일명에 명부 아동 이름이 들어있으면 표지 힌트로 채운다(코드리뷰 반영, #71).
     *
     * <p>파일명 서식을 파싱하지 않는다 — 기관마다 서식이 달라서 강제하면 도입 장벽이 된다.
     * 대신 명부 이름이 파일명에 포함되는지만 본다. 명부에 없는 값을 힌트로 넣으면 AI가
     * 본문도 안 보고 unmatched로 떨어뜨리므로(matching/nodes.py), 정확히 한 명으로
     * 좁혀지지 않으면 비워둔다 — 틀린 힌트가 힌트 없는 것보다 나쁘다.
     *
     * <p>macOS에서 만든 한글 파일명은 자모가 분리(NFD)돼서 온다 — 파일명·명부 이름 양쪽 다
     * NFC로 정규화하지 않으면 눈에는 같아 보이는데 contains가 실패한다.
     */
    private void applyHintFromFilename(RawRecord rawRecord, Long organizationId, String displayFilename) {
        String normalizedFilename = Normalizer.normalize(displayFilename, Normalizer.Form.NFC);

        List<Child> hits = childRepository.findByOrganizationId(organizationId).stream()
                .filter(child -> normalizedFilename.contains(Normalizer.normalize(child.getName(), Normalizer.Form.NFC)))
                .toList();

        if (hits.size() > 1) {
            // "박서연"과 "서연"이 둘 다 명부에 있으면 짧은 이름도 걸린다 — 가장 긴 매칭만 남긴다.
            int longestNameLength = hits.stream().mapToInt(child -> child.getName().length()).max().orElse(0);
            hits = hits.stream().filter(child -> child.getName().length() == longestNameLength).toList();
        }

        if (hits.size() == 1) {
            Child child = hits.get(0);
            rawRecord.applyHint(child.getName(), child.getBirthdate());
        }
        // 0명이거나(파일명에 이름 없음) 동명이인으로 여전히 2명 이상이면 힌트를 비워둔다.
    }

    public RawRecord getById(Long id, String userId) {
        String institutionId = resolveInstitutionId(userId);
        RawRecord record = rawRecordRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new RawRecordNotFoundException(id));
        // 다른 기관 소유 레코드는 "권한 없음"이 아니라 "없음"으로 응답한다 —
        // 403으로 응답하면 그 id가 실제로 존재한다는 사실 자체를 노출하게 된다.
        // 다만 응답과 별개로, 접근 거부는 감사 로그로 남긴다.
        if (!record.getInstitutionId().equals(institutionId)) {
            log.warn("denied cross-institution access id={} requester={} owner={}",
                    id, institutionId, record.getInstitutionId());
            throw new RawRecordNotFoundException(id);
        }
        return record;
    }

    public List<RawRecord> getByInstitution(String userId) {
        return rawRecordRepository.findByInstitutionIdAndDeletedAtIsNull(resolveInstitutionId(userId));
    }

    private String sanitizeDisplayName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "unknown";
        }
        String normalized = originalFilename.replace('\\', '/');
        String lastSegment = normalized.substring(normalized.lastIndexOf('/') + 1);
        return lastSegment.isBlank() ? "unknown" : lastSegment;
    }

    private String extractExtension(String displayFilename) {
        int dotIndex = displayFilename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == displayFilename.length() - 1) {
            return "";
        }
        return displayFilename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }
}
