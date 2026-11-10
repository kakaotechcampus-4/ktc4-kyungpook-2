package com.itda.backend.dto.response.summary;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SummaryAgentResponse(
        Long childId,
        String entryDate,
        Long institutionId,
        String content,
        JsonNode claims,
        JsonNode coveredEntryIds,
        JsonNode uncoveredEntryIds,
        Boolean needsReview,
        JsonNode reviewReasons,
        Boolean noContent,
        Boolean llmCalled) {

    public boolean isNoContent() {
        return Boolean.TRUE.equals(noContent);
    }
}
