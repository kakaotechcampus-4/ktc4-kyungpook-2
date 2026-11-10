package com.itda.backend.dto.response.insight;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record InsightAgentResponse(
        Long childId,
        String periodFrom,
        String periodTo,
        List<Insight> insights,
        Boolean llmCalled) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Insight(
            String situation,
            String behavior,
            String content,
            JsonNode supports,
            JsonNode claimIds,
            JsonNode sourceEntryIds,
            JsonNode sourceInstitutionIds,
            JsonNode institutionTypes,
            Boolean crossInstitution,
            JsonNode lookedUpClaimIds) {
    }
}
