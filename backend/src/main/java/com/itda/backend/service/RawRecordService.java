package com.itda.backend.service;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.itda.backend.domain.RawRecord;
import com.itda.backend.domain.RawRecordStatus;
import com.itda.backend.exception.RawRecordNotFoundException;
import com.itda.backend.exception.RawRecordStorageException;
import com.itda.backend.exception.RawRecordValidationException;
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
    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("csv", "txt", "pdf", "jpg", "jpeg", "png", "hwp");

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "text/csv", "text/plain", "application/pdf",
            "image/jpeg", "image/png", "application/x-hwp", "application/haansofthwp");

    private final RawRecordRepository rawRecordRepository;
    private final RawFileStorage rawFileStorage;
    private final UserService userService;

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
        String institutionId = resolveInstitutionId(userId);
        if (institutionId == null || institutionId.isBlank()) {
            throw new RawRecordValidationException("institutionId is required");
        }
        if (institutionId.length() > RawRecord.MAX_TEXT_FIELD_LENGTH) {
            throw new RawRecordValidationException("institutionId is too long");
        }
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
        return saved;
    }

    public RawRecord getById(Long id, String userId) {
        String institutionId = resolveInstitutionId(userId);
        RawRecord record = rawRecordRepository.findById(id)
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
        return rawRecordRepository.findByInstitutionId(resolveInstitutionId(userId));
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
