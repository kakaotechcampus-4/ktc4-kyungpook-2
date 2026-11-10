package com.itda.backend.dto.request.summary;

import java.util.List;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SummaryAgentRequest(
        Long childId,
        String childName,
        String entryDate,
        Long institutionId,
        String institutionName,
        List<Source> sources,
        List<String> otherChildNames) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Source(Long journalEntryId, String content, String entryDate) {
    }
}
