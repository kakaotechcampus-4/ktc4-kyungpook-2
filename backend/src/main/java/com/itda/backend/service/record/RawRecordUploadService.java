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

/**
 * 브라우저가 파일 본문을 저장소(S3)로 바로 올리는 업로드 (#450). 파일이 BE 를 거치지 않아 BE 메모리와 요청 스레드를 붙잡지 않는다.
 *
 * <ol>
 *   <li>{@link #issue} — 이름 · 형식 · 크기를 한 번에 올리는 업로드와 같은 기준으로 보고, 접수를 만들고 업로드 주소를 준다.</li>
 *   <li>브라우저가 주소로 본문을 올린다. BE 는 관여하지 않는다.</li>
 *   <li>{@link #complete} — 저장소의 파일을 확인하고 읽어 한 번에 올리는 업로드와 같은 흐름(나누기 → 상한 → 저장)으로 잇는다.
 *       이후(매칭 → 검증 → 요약)는 같다.</li>
 * </ol>
 *
 * <p><b>오류가 나는 단계.</b> 이름 · 형식 · 크기(400 · 413)는 주소를 줄 때 거절한다. 일지 수 · 길이(400
 * {@code RAW_RECORD_ENTRY_LIMIT_EXCEEDED}), 혼잡(429 {@code RAW_RECORD_UPLOAD_BUSY}), AI 처리 줄(429 {@code AGENT_QUEUE_FULL})은
 * 파일 내용을 봐야 알아 완료에서 거절한다. 일지 상한처럼 다시 해도 안 되는 거절은 파일과 접수를 지우고, 429 는 남겨 완료만 다시 부를 수
 * 있게 한다.
 *
 * <p><b>완료는 여러 번 불러도 된다.</b> 이미 완료된 접수는 만든 원본 기록을 그대로 돌려준다. 동시에 두 번 와도 접수 행 잠금으로
 * 한 번만 만든다 ({@link RawRecordRecorder#acceptUpload}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RawRecordUploadService {

    /** 정리 한 번에 지우는 접수 수. 다 못 지우면 이어서 부른다. */
    public static final int SWEEP_BATCH_SIZE = 100;

    private final RawRecordService rawRecordService;
    private final RawRecordRecorder rawRecordRecorder;
    private final RawRecordUploadRepository rawRecordUploadRepository;
    private final RawRecordRepository rawRecordRepository;
    private final RawFileStorage rawFileStorage;
    private final UserService userService;
    private final AgentQueueGuard agentQueueGuard;
    private final DirectUploadProperties properties;

    /** 한 번에 올리는 업로드(multipart)와 같은 상한이다. 저장소로 바로 올리면 Caddy 25MiB 상한이 걸리지 않아 여기서 지킨다. */
    @Value("${spring.servlet.multipart.max-file-size}")
    private DataSize maxFileSize;

    /**
     * 업로드 주소를 준다. 파일 본문은 아직 없다 — 이름 · 형식 · 크기만 본다.
     *
     * @throws RawRecordValidationException 크기가 없거나 0, 이름이 너무 길거나 확장자 · 형식이 허용되지 않는다
     * @throws RawRecordTooLargeException   상한보다 크다
     */
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
            // 접수 행은 남는다 — 파일이 없으니 정리 작업이 하루 뒤 지운다
            log.warn("failed to presign raw record upload id={}", upload.getId());
            throw new RawRecordStorageException("failed to presign upload", e);
        }
        log.info("raw record upload issued id={} organizationId={} sizeBytes={}",
                upload.getId(), organizationId, upload.getSizeBytes());
        return RawRecordUploadUrlResponse.of(upload, presigned);
    }

    /**
     * 다 올렸다는 알림을 받아 원본 기록을 만든다.
     *
     * @throws RawRecordUploadNotFoundException 없는 접수이거나 다른 기관의 접수다
     * @throws RawRecordUploadNotReadyException 저장소에 아직 파일이 없다. 접수는 남는다
     * @throws RawRecordValidationException     저장소의 파일 크기가 접수한 크기와 다르다. 파일과 접수를 지운다
     * @throws RawRecordEntryLimitException     일지가 너무 많거나 길다. 파일과 접수를 지운다
     */
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
            // 서명한 크기와 다르면 저장소가 받지 않는다. 그래도 왔다면 받지 않은 파일로 보고 지운다
            log.warn("uploaded file size mismatch id={} declared={} stored={}",
                    uploadId, upload.getSizeBytes(), storedSize.getAsLong());
            discard(upload);
            throw new RawRecordValidationException("uploaded file size does not match");
        }

        // 접수할 때 본 값이지만 같은 기준으로 다시 본다 — 확장자를 얻고, 그사이 허용 목록이 바뀐 경우도 막는다
        ValidatedFile file = rawRecordService.validate(upload.getOriginalFilename(), upload.getContentType());
        List<JournalEntrySplitter.SplitEntry> entries;
        try {
            entries = rawRecordService.splitWithinLimits(file.extension(),
                    () -> rawFileStorage.read(upload.getStoredPath()));
        } catch (RawRecordEntryLimitException e) {
            // 다시 해도 같은 파일이라 받을 수 없다. 받지 않은 파일이라 append-only 대상이 아니다 — 지운다
            discard(upload);
            throw e;
        }
        // 흔한 경우는 저장 전에 거절한다. 잠그지 않고 센 값이라 상한을 지키지는 않는다 — 아래 acceptUpload 가 다시 센다.
        // 429 는 파일과 접수를 남긴다 — 잠시 뒤 완료만 다시 부르면 된다
        agentQueueGuard.peek(organizationId, entries.size());

        RawRecord draft = rawRecordService.draft(organizationId, file, upload.getStoredPath(), upload.getSizeBytes());
        RawRecord saved;
        try {
            saved = rawRecordRecorder.acceptUpload(uploadId, draft, organizationId, rawRecordService.dated(draft, entries));
        } catch (AgentQueueFullException | RawRecordUploadNotFoundException e) {
            throw e;
        } catch (RuntimeException e) {
            // 접수와 파일은 그대로 둔다 — 완료를 다시 부르면 이어서 받을 수 있다
            log.error("failed to persist raw record for upload id={}; upload is kept", uploadId, e);
            throw new RawRecordStorageException("failed to persist raw record metadata", e);
        }
        log.info("raw record upload completed id={} rawRecordId={} journalEntries={}",
                uploadId, saved.getId(), entries.size());
        return saved;
    }

    /**
     * 이 기관의 접수. 다른 기관 것이면 없는 것처럼 404 다 — 있는지조차 알리지 않는다. 로컬 업로드 본문을 받을 때도 쓴다.
     *
     * @throws RawRecordUploadNotFoundException 없거나 다른 기관의 접수다
     */
    public RawRecordUpload ownedUpload(String userId, Long uploadId) {
        return owned(userService.getOrganizationIdOf(userId), uploadId);
    }

    /**
     * 오래된 접수를 한 묶음 지운다. 완료되지 않은 접수는 저장소 파일도 지울 대상으로 돌려준다 — 파일은 부르는 쪽이 커밋 뒤에 지운다.
     * 완료된 접수는 행만 지운다. 파일은 원본 기록의 것이다.
     */
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

    /**
     * @param uploads      지운 접수 수
     * @param abandonedKeys 완료되지 않은 접수의 저장소 파일. 커밋 뒤 지운다
     */
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

    /** 받지 않은 파일과 그 접수를 지운다. 파일은 행을 지운 뒤 지운다 — 지우기 실패는 저장소가 로그로 남긴다. */
    private void discard(RawRecordUpload upload) {
        rawRecordUploadRepository.deleteById(upload.getId());
        rawFileStorage.delete(upload.getStoredPath());
    }
}
