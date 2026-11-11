package com.itda.backend.dto.response.summary;

import java.time.LocalDate;
import java.util.List;

public record FailedSummaryGroupResponse(
        Long childId,
        String childName,
        LocalDate entryDate,
        List<Entry> entries,
        String reason) {

    public record Entry(Long journalEntryId, String content) {
    }
}
