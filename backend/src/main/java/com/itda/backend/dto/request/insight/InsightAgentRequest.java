package com.itda.backend.dto.request.insight;

import java.util.List;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record InsightAgentRequest(
        Long childId,
        String childName,
        String periodFrom,
        String periodTo,
        List<Claim> claims,
        List<Institution> consentedInstitutions,
        List<Claim> historyClaims) {

    public InsightAgentRequest {
        claims = claims == null ? List.of() : claims;
        consentedInstitutions = consentedInstitutions == null ? List.of() : consentedInstitutions;
        historyClaims = historyClaims == null ? List.of() : historyClaims;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Claim(
            String claimId,
            String text,
            String entryDate,
            Long institutionId,
            String institutionType,
            ClaimSource source,
            List<Evidence> evidence) {
    }

    public enum ClaimSource {
        AI,
        EDITED,
        TEACHER
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Evidence(Long journalEntryId, String quote) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Institution(Long institutionId, String institutionType) {
    }
}
