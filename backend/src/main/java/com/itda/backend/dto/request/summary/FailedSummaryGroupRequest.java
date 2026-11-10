package com.itda.backend.dto.request.summary;

import com.itda.backend.dto.request.child.RegisterChildRequest;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record FailedSummaryGroupRequest(
        @NotNull
        Long childId,

        @Schema(type = "string", format = "date", example = "2026-10-08")
        @NotNull
        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$")
        String entryDate
) {

    @JsonIgnore
    @AssertTrue
    public boolean isEntryDateValid() {
        if (entryDate == null) {
            return true;
        }
        try {
            LocalDate.parse(entryDate);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    public LocalDate entryDateValue() {
        return LocalDate.parse(entryDate);
    }
}
