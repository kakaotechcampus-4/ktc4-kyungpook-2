package com.itda.backend.service.record;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.OptionalLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.record.RawRecordUpload;
import com.itda.backend.dto.request.record.RawRecordUploadUrlRequest;
import com.itda.backend.dto.response.record.RawRecordUploadUrlResponse;
import com.itda.backend.exception.agent.AgentQueueFullException;
import com.itda.backend.exception.record.RawRecordEntryLimitException;
import com.itda.backend.exception.record.RawRecordStorageException;
import com.itda.backend.exception.record.RawRecordTooLargeException;
import com.itda.backend.exception.record.RawRecordUploadNotFoundException;
import com.itda.backend.exception.record.RawRecordUploadNotReadyException;
import com.itda.backend.exception.record.RawRecordValidationException;
import com.itda.backend.repository.record.RawRecordRepository;
import com.itda.backend.repository.record.RawRecordUploadRepository;
import com.itda.backend.service.agent.AgentQueueGuard;
import com.itda.backend.service.record.RawRecordService.ValidatedFile;
import com.itda.backend.service.storage.PresignedUpload;
import com.itda.backend.service.storage.RawFileStorage;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class RawRecordUploadService {

    public static final int SWEEP_BATCH_SIZE = 100;

    private final RawRecordService rawRecordService;
    private final RawRecordRecorder rawRecordRecorder;
    private final RawRecordUploadRepository rawRecordUploadRepository;
    private final RawRecordRepository rawRecordRepository;
    private final RawFileStorage rawFileStorage;
    private final UserService userService;
    private final AgentQueueGuard agentQueueGuard;
    private final DirectUploadProperties properties;

    @Value("${spring.servlet.multipart.max-file-size}")
    private DataSize maxFileSize;

    public RawRecordUploadUrlResponse issue(String userId, RawRecordUploadUrlRequest request) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        if (request == null || request.size() == null || request.size() <= 0) {
            throw new RawRecordValidationException("file is required");
        }
        if (request.size() > maxFileSize.toBytes()) {
            throw new RawRecordTooLargeException("declared size exceeds the limit: " + request.size());
        }
        ValidatedFile file = rawRecordService.validate(request.filename(), request.contentType());

        RawRecordUpload upload = rawRecordUploadRepository.save(new RawRecordUpload(
                organizationId, Long.valueOf(userId), file.displayName(), RawFileStorage.newKey(file.extension()),
                file.contentType(), request.size()));
        PresignedUpload presigned;
        try {
            presigned = rawFileStorage.presignUpload(upload.getId(), upload.getStoredPath(), upload.getContentType(),
                    upload.getSizeBytes(), properties.urlTtl());
        } catch (RuntimeException e) {
            log.warn("failed to presign raw record upload id={}", upload.getId());
            throw new RawRecordStorageException("failed to presign upload", e);
        }
        log.info("raw record upload issued id={} organizationId={} sizeBytes={}",
                upload.getId(), organizationId, upload.getSizeBytes());
        return RawRecordUploadUrlResponse.of(upload, presigned);
    }

    public RawRecord complete(String userId, Long uploadId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        RawRecordUpload upload = owned(organizationId, uploadId);
        if (upload.isCompleted()) {
            return completedRecord(upload);
        }

        OptionalLong storedSize;
        try {
            storedSize = rawFileStorage.sizeOf(upload.getStoredPath());
        } catch (IOException | RuntimeException e) {
            throw new RawRecordStorageException("failed to check the uploaded file", e);
        }
        if (storedSize.isEmpty()) {
            throw new RawRecordUploadNotReadyException(uploadId);
        }
        if (storedSize.getAsLong() != upload.getSizeBytes()) {
            log.warn("uploaded file size mismatch id={} declared={} stored={}",
                    uploadId, upload.getSizeBytes(), storedSize.getAsLong());
            discard(upload);
            throw new RawRecordValidationException("uploaded file size does not match");
        }

        ValidatedFile file = rawRecordService.validate(upload.getOriginalFilename(), upload.getContentType());
        List<JournalEntrySplitter.SplitEntry> entries;
        try {
            entries = rawRecordService.splitWithinLimits(file.extension(),
                    () -> rawFileStorage.read(upload.getStoredPath()));
        } catch (RawRecordEntryLimitException e) {
            discard(upload);
            throw e;
        }
        agentQueueGuard.peek(organizationId, entries.size());

        RawRecord draft = rawRecordService.draft(organizationId, file, upload.getStoredPath(), upload.getSizeBytes());
        RawRecord saved;
        try {
            saved = rawRecordRecorder.acceptUpload(uploadId, draft, organizationId, rawRecordService.dated(draft, entries));
        } catch (AgentQueueFullException | RawRecordUploadNotFoundException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("failed to persist raw record for upload id={}; upload is kept", uploadId, e);
            throw new RawRecordStorageException("failed to persist raw record metadata", e);
        }
        log.info("raw record upload completed id={} rawRecordId={} journalEntries={}",
                uploadId, saved.getId(), entries.size());
        return saved;
    }

    public RawRecordUpload ownedUpload(String userId, Long uploadId) {
        return owned(userService.getOrganizationIdOf(userId), uploadId);
    }

    @Transactional
    public Swept sweepBatch(LocalDateTime now) {
        List<RawRecordUpload> old = rawRecordUploadRepository.findByCreatedAtBeforeOrderByIdAsc(
                now.minus(properties.abandonAfter()), Limit.of(SWEEP_BATCH_SIZE));
        List<String> abandonedKeys = old.stream()
                .filter(upload -> !upload.isCompleted())
                .map(RawRecordUpload::getStoredPath)
                .toList();
        rawRecordUploadRepository.deleteAllInBatch(old);
        return new Swept(old.size(), abandonedKeys);
    }

    public record Swept(int uploads, List<String> abandonedKeys) {
    }

    private RawRecordUpload owned(Long organizationId, Long uploadId) {
        RawRecordUpload upload = rawRecordUploadRepository.findById(uploadId)
                .orElseThrow(() -> new RawRecordUploadNotFoundException(uploadId));
        if (!upload.getOrganizationId().equals(organizationId)) {
            log.warn("denied cross-institution upload access id={} requester={} owner={}",
                    uploadId, organizationId, upload.getOrganizationId());
            throw new RawRecordUploadNotFoundException(uploadId);
        }
        return upload;
    }

    private RawRecord completedRecord(RawRecordUpload upload) {
        return rawRecordRepository.findById(upload.getRawRecordId())
                .orElseThrow(() -> new RawRecordUploadNotFoundException(upload.getId()));
    }

    private void discard(RawRecordUpload upload) {
        rawRecordUploadRepository.deleteById(upload.getId());
        rawFileStorage.delete(upload.getStoredPath());
    }
}
