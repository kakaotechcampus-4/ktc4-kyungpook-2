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

@Slf4j
@Hidden
@RestController
@Profile("!docker")
@RequiredArgsConstructor
public class LocalRawUploadController {

    private final RawRecordUploadService rawRecordUploadService;
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
