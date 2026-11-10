package com.itda.backend.dto.response.agent;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiProgressItem(
        String agent,
        @JsonProperty("journal_entry_id") Long journalEntryId,
        @JsonProperty("child_id") Long childId,
        @JsonProperty("entry_date") LocalDate entryDate,
        @JsonProperty("institution_id") Long institutionId,
        @JsonProperty("period_from") LocalDate periodFrom,
        String node,
        @JsonProperty("elapsed_sec") int elapsedSec) {
}
