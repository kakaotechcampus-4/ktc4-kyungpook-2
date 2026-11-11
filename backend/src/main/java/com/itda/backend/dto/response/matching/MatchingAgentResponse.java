package com.itda.backend.dto.response.matching;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.itda.backend.domain.matching.MatchingStatus;
import com.itda.backend.domain.matching.MultiReason;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record MatchingAgentResponse(
        Long journalEntryId,
        MatchingStatus status,
        Long matchedChildId,
        Double confidence,
        Boolean hintMismatch,
        JsonNode evidence,
        JsonNode mentionedChildIds,
        MultiReason multiReason,
        JsonNode candidates,
        Boolean llmCalled) {
}
