package com.itda.backend.dto.request.validation;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ValidationAgentRequest(
        Long journalEntryId,
        String content,
        Long subjectChildId,
        String subjectName) {
}
