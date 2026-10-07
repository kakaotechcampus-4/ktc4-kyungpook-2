package com.itda.backend.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

import com.itda.backend.domain.Child;
import com.itda.backend.domain.JournalEntry;
import com.itda.backend.domain.RawRecord;
import com.itda.backend.domain.RawRecordStatus;
import com.itda.backend.exception.RawRecordNotFoundException;
import com.itda.backend.exception.RawRecordStorageException;
import com.itda.backend.exception.RawRecordValidationException;
import com.itda.backend.repository.ChildRepository;
import com.itda.backend.repository.JournalEntryRepository;
import com.itda.backend.repository.RawRecordRepository;
import com.itda.backend.service.storage.RawFileStorage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class RawRecordService {

    // ponytail: 매직바이트 스니핑 없이 확장자/선언된 content-type 이중 화이트리스트로만 검증.
    // 팀 합의로 파일 종류가 늘어나면 이 목록만 넓히면 됨.
    // jpg/jpeg/png/hwp는 이번 학기 범위 밖으로 제외(노션 "파일 형식 결정" 문서, 2026-09-29) —
    // jpg/png는 OCR 필요, hwp는 자바 파싱이 매우 어려움. docx는 아직 추가 전(다음 이슈).
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("csv", "txt", "pdf");

    // application/vnd.ms-excel: Windows가 .csv를 저장할 때 흔히 이 Content-Type으로 보낸다
    // (#113, 최재혁님 리뷰) — 실제로 정상 csv 파일이 이 값 때문에 거부됐었다.
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("text/csv", "application/vnd.ms-excel", "text/plain", "application/pdf");

    // 텍스트 추출이 아직 안 되는 형식 — 업로드는 받되 기록 분리는 건너뛴다(entries: [] 유지).
    // pdf 추출(PDFBox)은 다음 이슈에서 추가한다.
    private static final Set<String> TEXT_EXTRACTABLE_EXTENSIONS = Set.of("csv", "txt");

    // "8월21일" · "2026년 8월 21일" — 한글 표기는 뜻이 분명해서 가장 먼저 본다.
    private static final Pattern FILENAME_DATE_KOREAN =
            Pattern.compile("(?:(20\\d{2})\\s*년\\s*)?(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일");

    // "2026-08-21" · "2026.08.21" · "20260821" — 네 자리 연도가 있으면 그대로 믿는다.
    private static final Pattern FILENAME_DATE_WITH_YEAR =
            Pattern.compile("(20\\d{2})[-._]?(\\d{1,2})[-._]?(\\d{1,2})");

    // "0821_관찰일지.txt" 의 MMDD. 앞뒤에 다른 숫자가 붙어 있으면(전화번호·학번 등) 보지 않는다.
    private static final Pattern FILENAME_DATE_MONTH_DAY =
            Pattern.compile("(?<!\\d)(\\d{2})(\\d{2})(?!\\d)");

    private final RawRecordRepository rawRecordRepository;
    private final RawRecordRecorder rawRecordRecorder;
    private final RawFileStorage rawFileStorage;
    private final UserService userService;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalEntrySplitter journalEntrySplitter;
    private final ChildRepository childRepository;

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

    public RawRecord ingest(String userId, MultipartFile file) {
        // 기관 식별자는 이제 사용자의 소속 기관 id(숫자)라, 비어 있거나 컬럼 길이를 넘을 수 없다.
        // 예전에 있던 두 검사는 클라이언트가 보낸 값을 믿던 시절의 것이라 지웠다.
        Long organizationId = userService.getOrganizationIdOf(userId);
        String institutionId = String.valueOf(organizationId);
        if (file == null || file.isEmpty()) {
            throw new RawRecordValidationException("file is required");
        }

        String displayFilename = sanitizeDisplayName(file.getOriginalFilename());
        if (displayFilename.length() > RawRecord.MAX_TEXT_FIELD_LENGTH) {
            throw new RawRecordValidationException("file name is too long");
        }

        String extension = extractExtension(displayFilename);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new RawRecordValidationException("unsupported file extension");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new RawRecordValidationException("unsupported content type");
        }

        // rawFileStorage.store()보다 먼저 읽어둔다 — MultipartFile.transferTo()는 구현에 따라
        // 파일을 "이동"시킬 수 있어서(Spring Javadoc), store() 이후에 다시 읽으면 실패할 수 있다.
        // 여기서 실패해도 업로드 자체는 막지 않는다 — 못 읽으면 그냥 분리를 건너뛴다
        // (store()가 곧이어 독자적으로 다시 읽는데, 정말 스트림이 깨졌다면 거기서도 실패해서
        // 기존 RawRecordStorageException 경로로 자연스럽게 처리된다).
        byte[] fileBytes = null;
        if (TEXT_EXTRACTABLE_EXTENSIONS.contains(extension)) {
            try {
                fileBytes = file.getBytes();
            } catch (IOException e) {
                log.warn("failed to pre-read raw file bytes for splitting institutionId={}", institutionId, e);
            }
        }

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

        RawRecord rawRecord = new RawRecord(
                institutionId,
                displayFilename,
                storedPath,
                contentType,
                file.getSize(),
                RawRecordStatus.PENDING);
        applyHintFromFilename(rawRecord, organizationId, displayFilename);

        RawRecord saved;
        try {
            saved = rawRecordRecorder.save(rawRecord);
        } catch (RuntimeException e) {
            // 원본은 append-only — DB 저장이 실패해도 이미 저장소에 올라간 파일은 지우지 않는다.
            // 대신 FAILED 상태로 별도 기록을 남겨서, 나중에 추적/재처리할 수 있게 한다.
            log.error("failed to persist raw record metadata for storedPath={}; raw file is kept", storedPath, e);
            try {
                rawRecordRecorder.recordFailure(institutionId, displayFilename, storedPath, contentType, file.getSize());
            } catch (RuntimeException retryFailure) {
                log.error("failed to record FAILED status for storedPath={}; "
                        + "raw file remains untracked in DB but preserved in storage", storedPath, retryFailure);
            }
            throw new RawRecordStorageException("failed to persist raw record metadata", e);
        }

        log.info("raw record intake recorded id={} institutionId={} status={}",
                saved.getId(), institutionId, saved.getStatus());

        splitIntoJournalEntries(saved, fileBytes);

        return saved;
    }

    /**
     * 업로드된 파일을 기록(JournalEntry) 단위로 쪼개서 저장한다. 응답(201)은 이미 위에서
     * 확정된 값을 그대로 돌려주므로, 여기서 실패해도 업로드 자체는 성공으로 남는다 —
     * 파일 저장에 실패하는 것과는 무게가 다르다(원본은 이미 안전하게 저장됨).
     *
     * <p>워커가 아니라 요청 안에서 동기로 처리한다 — AI 호출(매칭)은 여기서 하지 않는다,
     * 그건 별도 워커가 PENDING 상태의 JournalEntry를 긁어가서 한다(노션 "기록 분리 기능" 문서).
     */
    private void splitIntoJournalEntries(RawRecord saved, byte[] fileBytes) {
        if (fileBytes == null) {
            // pdf 등 아직 텍스트를 못 뽑는 형식이거나, 사전 읽기 자체가 실패한 경우 —
            // entries가 계속 비어있는 게 정상이다(O-26).
            return;
        }

        String text = new String(fileBytes, StandardCharsets.UTF_8);
        List<JournalEntrySplitter.SplitEntry> entries = journalEntrySplitter.split(text);
        LocalDate fallbackDate = resolveFallbackDate(saved, entries);
        try {
            int seq = 1;
            for (JournalEntrySplitter.SplitEntry entry : entries) {
                LocalDate entryDate = entry.entryDate() == null ? fallbackDate : entry.entryDate();
                journalEntryRepository.save(
                        JournalEntry.of(saved.getId(), entryDate, entry.content(), seq++));
            }
            log.info("split raw record id={} into {} journal entries", saved.getId(), entries.size());
        } catch (RuntimeException e) {
            // 업로드는 이미 성공했다 — 기록 분리 실패로 업로드 응답까지 실패시키지 않는다.
            log.error("failed to save journal entries for rawRecordId={}", saved.getId(), e);
        }
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
    private LocalDate resolveFallbackDate(RawRecord saved, List<JournalEntrySplitter.SplitEntry> entries) {
        LocalDate firstDateInFile = entries.stream()
                .map(JournalEntrySplitter.SplitEntry::entryDate)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (firstDateInFile != null) {
            return firstDateInFile;
        }

        LocalDate uploadedOn = saved.getCreatedAt().toLocalDate();
        return parseDateFromFilename(saved.getOriginalFilename(), uploadedOn).orElse(uploadedOn);
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
        Matcher korean = FILENAME_DATE_KOREAN.matcher(filename);
        while (korean.find()) {
            int year = korean.group(1) == null ? uploadedOn.getYear() : Integer.parseInt(korean.group(1));
            Optional<LocalDate> parsed = toDate(
                    year, Integer.parseInt(korean.group(2)), Integer.parseInt(korean.group(3)));
            if (parsed.isPresent()) {
                return parsed;
            }
        }

        Matcher full = FILENAME_DATE_WITH_YEAR.matcher(filename);
        while (full.find()) {
            Optional<LocalDate> parsed = toDate(
                    Integer.parseInt(full.group(1)), Integer.parseInt(full.group(2)), Integer.parseInt(full.group(3)));
            if (parsed.isPresent()) {
                return parsed;
            }
        }

        Matcher short4 = FILENAME_DATE_MONTH_DAY.matcher(filename);
        while (short4.find()) {
            Optional<LocalDate> parsed = toDate(
                    uploadedOn.getYear(), Integer.parseInt(short4.group(1)), Integer.parseInt(short4.group(2)));
            if (parsed.isPresent()) {
                return parsed;
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
