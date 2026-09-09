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
        if (file == null || file.isEmpty()) {
            throw new RawRecordValidationException("file is required");
        }

        String extension = extractExtension(file.getOriginalFilename());
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
                sanitizeDisplayName(file.getOriginalFilename()),
                storedPath,
                contentType,
                file.getSize(),
                RawRecordStatus.PENDING);

        RawRecord saved = rawRecordRepository.save(rawRecord);
        log.info("raw record intake recorded id={} institutionId={} status={}",
                saved.getId(), institutionId, saved.getStatus());
        return saved;
    }

    public RawRecord getById(Long id) {
        return rawRecordRepository.findById(id)
                .orElseThrow(() -> new RawRecordNotFoundException(id));
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

    private String extractExtension(String originalFilename) {
        String displayName = sanitizeDisplayName(originalFilename);
        int dotIndex = displayName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == displayName.length() - 1) {
            return "";
        }
        return displayName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }
}
