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

    public RawRecord ingest(String institutionId, MultipartFile file) {
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
        } catch (IOException e) {
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

    public RawRecord getById(Long id, String institutionId) {
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

    public List<RawRecord> getByInstitution(String institutionId) {
        return rawRecordRepository.findByInstitutionId(institutionId);
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
