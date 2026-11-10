package com.itda.backend.dto.response.child;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import io.swagger.v3.oas.annotations.media.Schema;

public record ParentActivityResponse(
        String id,
        @Schema(allowableValues = {"journal", "insight"}) String kind,
        String at,
        String text,
        String journalId,
        String insightId) {

    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static ParentActivityResponse journal(String journalId, LocalDateTime at, String text) {
        return new ParentActivityResponse("journal-" + journalId, "journal", at.format(AT), text, journalId, null);
    }

    public static ParentActivityResponse insight(String key, LocalDateTime at, String text, String insightId) {
        return new ParentActivityResponse("insight-" + key, "insight", at.format(AT), text, null, insightId);
    }
}
