package com.itda.backend.dto.request.notify;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NotifyAgentRequest(
        @JsonProperty("child_id") Long childId,
        @JsonProperty("child_name") String childName,
        @JsonProperty("period_from") String periodFrom,
        @JsonProperty("period_to") String periodTo,
        List<ApprovedInsight> insights,
        @JsonProperty("notification_id") String notificationId,
        @JsonProperty("other_child_names") List<String> otherChildNames) {

    public record ApprovedInsight(
            String content,
            @JsonProperty("institution_name") String institutionName,
            @JsonProperty("period_label") String periodLabel,
            @JsonProperty("institution_types") List<String> institutionTypes,
            @JsonProperty("cross_institution") boolean crossInstitution,
            List<EvidenceEntry> evidence) {
    }

    public record EvidenceEntry(
            @JsonProperty("institution_name") String institutionName,
            String date,
            @JsonProperty("looked_up") boolean lookedUp,
            List<EvidenceSentence> sentences) {
    }

    public record EvidenceSentence(String text, String role) {
    }
}
