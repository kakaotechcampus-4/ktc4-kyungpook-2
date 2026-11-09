package com.itda.backend.dto.response.summary;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

public record SummaryGroupBoardResponse(MatchingCounts matching, List<SummaryGroupCard> groups) {

    public record MatchingCounts(long inProgress, long needsReview) {
    }

    public record SummaryGroupCard(
            Long childId,
            String childName,
            LocalDate birthdate,
            LocalDate entryDate,
            @Schema(allowableValues = {"needs_fix", "ready", "summarizing", "validating", "admin_check"})
            String status,
            Counts counts,
            boolean canSummarize,
            boolean summaryRequested,
            long waitingCount,
            boolean previousSummaryInReview) {
    }

    public record Counts(long validating, long needsFix, long ready, long summarizing, long adminCheck) {
    }
}
