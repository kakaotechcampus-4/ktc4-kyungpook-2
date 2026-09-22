package com.itda.backend.dto;

import java.time.LocalDateTime;

import com.itda.backend.domain.RawRecord;
import com.itda.backend.domain.RawRecordStatus;

public record RawRecordResponse(
        Long id,
        String institutionId,
        String originalFilename,
        String contentType,
        long sizeBytes,
        RawRecordStatus status,
        LocalDateTime createdAt) {

    public static RawRecordResponse from(RawRecord rawRecord) {
        return new RawRecordResponse(
                rawRecord.getId(),
                rawRecord.getInstitutionId(),
                rawRecord.getOriginalFilename(),
                rawRecord.getContentType(),
                rawRecord.getSizeBytes(),
                rawRecord.getStatus(),
                rawRecord.getCreatedAt());
    }
}
