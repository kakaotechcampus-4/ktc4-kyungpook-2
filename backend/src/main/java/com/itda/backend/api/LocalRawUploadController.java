package com.itda.backend.api;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import com.itda.backend.domain.record.RawRecordUpload;
import com.itda.backend.exception.record.RawRecordUploadNotFoundException;
import com.itda.backend.exception.record.RawRecordValidationException;
import com.itda.backend.service.record.RawRecordUploadService;
import com.itda.backend.service.storage.LocalDiskRawFileStorage;
import com.itda.backend.service.storage.RawFileStorage;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 로컬(S3 없음)에서 업로드 주소가 가리키는 곳 (#450). 운영에서는 브라우저가 S3 로 바로 올리고 이 경로는 없다.
 *
 * <p>S3 의 서명 주소가 하는 확인을 같은 기준으로 한다 — 이 기관의 접수인지, 형식이 접수와 같은지, 본문 크기가 접수와 같은지.
 * 다른 API 처럼 로그인 · CSRF 를 본다(보안 설정을 풀지 않는다). 운영 문서에 없는 경로라 Swagger 에 싣지 않는다.
 */
@Slf4j
@Hidden
@RestController
@Profile("!docker")
@RequiredArgsConstructor
public class LocalRawUploadController {

    private final RawRecordUploadService rawRecordUploadService;
    /**
     * 인터페이스로 받는다 — 테스트가 저장소를 mock 으로 바꿔도 이 컨트롤러 때문에 앱이 뜨지 못하는 일이 없게. 로컬 디스크 저장소일 때만
     * 본문을 받는다.
     */
    private final RawFileStorage rawFileStorage;

    @PutMapping("/api/v1/raw-records/uploads/{uploadId}/content")
    public ResponseEntity<Void> content(@AuthenticationPrincipal String userId, @PathVariable Long uploadId,
            HttpServletRequest request) {
        if (!(rawFileStorage instanceof LocalDiskRawFileStorage localDiskRawFileStorage)) {
            throw new RawRecordUploadNotFoundException(uploadId);
        }
        RawRecordUpload upload = rawRecordUploadService.ownedUpload(userId, uploadId);
        if (upload.isCompleted()) {
            throw new RawRecordValidationException("upload is already completed");
        }
        if (!upload.getContentType().equals(request.getContentType())) {
            throw new RawRecordValidationException("content type does not match the upload");
        }
        long written;
        try {
            written = localDiskRawFileStorage.write(upload.getStoredPath(), request.getInputStream(),
                    upload.getSizeBytes());
        } catch (IOException e) {
            localDiskRawFileStorage.delete(upload.getStoredPath());
            throw new RawRecordValidationException("upload body exceeds the declared size");
        }
        if (written != upload.getSizeBytes()) {
            localDiskRawFileStorage.delete(upload.getStoredPath());
            throw new RawRecordValidationException("upload body size does not match");
        }
        return ResponseEntity.ok().build();
    }
}
