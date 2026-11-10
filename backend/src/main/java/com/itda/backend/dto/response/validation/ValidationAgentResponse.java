package com.itda.backend.dto.response.validation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.itda.backend.domain.validation.ValidationVerdict;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ValidationAgentResponse(
        Long journalEntryId,
        ValidationVerdict verdict,
        JsonNode issueTypes,
        JsonNode evidence) {
}
