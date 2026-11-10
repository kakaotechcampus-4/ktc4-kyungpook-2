package com.itda.backend.dto.response.validation;

import com.itda.backend.dto.response.record.RecordResponse;

public record ValidationQueueItemResponse(
        String id,
        Long journalEntryId,
        RecordResponse record,
        String childName,
        String violationReason,
        java.util.List<String> violationCode) {
}
