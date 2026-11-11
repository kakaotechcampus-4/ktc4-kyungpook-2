package com.itda.backend.dto.request.matching;

import java.util.List;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MatchingAgentRequest(
        Long journalEntryId,
        String content,
        List<RosterEntry> roster,
        Long rawRecordId,
        String entryDate,
        String hintName,
        String hintBirthdate) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RosterEntry(Long childId, String name, String birthdate) {
    }
}
