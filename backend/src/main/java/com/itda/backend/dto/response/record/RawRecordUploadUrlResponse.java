package com.itda.backend.dto.response.record;

import java.time.Instant;
import java.util.Map;

import com.itda.backend.domain.record.RawRecordUpload;
import com.itda.backend.service.storage.PresignedUpload;

public record RawRecordUploadUrlResponse(
        Long uploadId, String uploadUrl, String method, Map<String, String> headers, Instant expiresAt) {

    public static RawRecordUploadUrlResponse of(RawRecordUpload upload, PresignedUpload presigned) {
        return new RawRecordUploadUrlResponse(
                upload.getId(), presigned.url(), "PUT", presigned.headers(), presigned.expiresAt());
    }
}
