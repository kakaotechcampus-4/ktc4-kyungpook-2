package com.itda.backend.dto.response.summary;

import java.time.LocalDate;
import java.util.List;

public record Gate1SummaryResponse(
        Long id,
        Long childId,
        String childName,
        LocalDate entryDate,
        Integer revision,
        String status,
        String version,
        boolean processing,
        String content,
        List<ClaimCard> claims,
        boolean noContent,
        boolean needsReview,
        List<String> reviewReasons,
        List<UncoveredEntry> uncoveredEntries,
        List<ReviewFlag> reviewFlags,
        List<SourceEntry> entries) {

    public record ClaimCard(int index, String text, List<ClaimEvidence> evidence) {
    }

    public record ClaimEvidence(Long journalEntryId, String quote) {
    }

    public record UncoveredEntry(Long journalEntryId, String content) {
    }

    public record SourceEntry(Long journalEntryId, String content) {
    }

    public record ReviewFlag(Long journalEntryId, List<String> issueTypes, List<String> reasons,
            List<Integer> claimIndexes) {
    }
}
