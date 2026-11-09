package com.itda.backend.dto.response.record;

import java.time.LocalDateTime;
import java.util.List;

public record RecordProgressResponse(
        String rawRecordId,
        String fileName,
        LocalDateTime uploadedAt,
        List<EntryProgress> entries) {

    public record EntryProgress(String id, int stageIndex, String state) {
    }
}
