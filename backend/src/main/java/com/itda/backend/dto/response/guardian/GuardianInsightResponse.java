package com.itda.backend.dto.response.guardian;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.itda.backend.dto.response.organization.InstitutionResponse;

public record GuardianInsightResponse(
        String id,
        String childId,
        String childName,
        String period,
        String periodFrom,
        String periodTo,
        String content,
        InstitutionResponse from,
        List<InstitutionResponse> sharedWith,
        LocalDateTime approvedAt,
        LocalDateTime readAt,
        List<Evidence> evidence,
        String kind,
        String quietReason) {

    public static final String INSIGHT = "insight";
    public static final String QUIET = "quiet";

    public static GuardianInsightResponse quiet(String childId, String childName, String period, LocalDate periodFrom,
            LocalDate periodTo, String quietReason) {
        return new GuardianInsightResponse("quiet-" + childId + "-" + periodFrom, childId, childName, period,
                periodFrom.toString(), periodTo.toString(), null, null, List.of(),
                periodTo.plusDays(1).atStartOfDay(), null, List.of(), QUIET, quietReason);
    }

    public record Evidence(String journalEntryId, String date, InstitutionResponse institution,
            boolean lookedUp, List<Sentence> sentences) {
    }

    public record Sentence(String text, String role) {
    }
}
