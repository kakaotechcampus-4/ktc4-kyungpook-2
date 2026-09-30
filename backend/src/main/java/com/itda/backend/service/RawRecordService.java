package com.itda.backend.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.itda.backend.domain.JournalEntry;
import com.itda.backend.domain.RawRecord;
import com.itda.backend.domain.RawRecordStatus;
import com.itda.backend.exception.RawRecordNotFoundException;
import com.itda.backend.exception.RawRecordStorageException;
import com.itda.backend.exception.RawRecordValidationException;
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

    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("text/csv", "text/plain", "application/pdf");

    // 텍스트 추출이 아직 안 되는 형식 — 업로드는 받되 기록 분리는 건너뛴다(entries: [] 유지).
    // pdf 추출(PDFBox)은 다음 이슈에서 추가한다.
    private static final Set<String> TEXT_EXTRACTABLE_EXTENSIONS = Set.of("csv", "txt");

    private final RawRecordRepository rawRecordRepository;
    private final RawFileStorage rawFileStorage;
    private final UserService userService;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalEntrySplitter journalEntrySplitter;

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
        String institutionId = resolveInstitutionId(userId);
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

        RawRecord saved;
        try {
            saved = rawRecordRepository.save(rawRecord);
        } catch (RuntimeException e) {
            // 원본은 append-only — DB 저장이 실패해도 이미 저장소에 올라간 파일은 지우지 않는다.
            // 대신 FAILED 상태로 별도 기록을 남겨서, 나중에 추적/재처리할 수 있게 한다.
            log.error("failed to persist raw record metadata for storedPath={}; raw file is kept", storedPath, e);
            try {
                rawRecordRepository.save(new RawRecord(
                        institutionId, displayFilename, storedPath, contentType, file.getSize(),
                        RawRecordStatus.FAILED));
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
        try {
            int seq = 1;
            for (JournalEntrySplitter.SplitEntry entry : entries) {
                journalEntryRepository.save(
                        JournalEntry.of(saved.getId(), entry.entryDate(), entry.content(), seq++));
            }
            log.info("split raw record id={} into {} journal entries", saved.getId(), entries.size());
        } catch (RuntimeException e) {
            // 업로드는 이미 성공했다 — 기록 분리 실패로 업로드 응답까지 실패시키지 않는다.
            log.error("failed to save journal entries for rawRecordId={}", saved.getId(), e);
        }
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
