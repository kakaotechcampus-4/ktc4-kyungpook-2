package com.itda.backend.dto.request.child;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterChildRequest(
        @NotBlank
        @Size(max = 100)
        String name,

        @Schema(type = "string", format = "date", example = "2017-03-14")
        @NotNull
        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$")
        String birthDate,

        @Schema(example = "2026-0031")
        @NotBlank
        @Size(max = 50)
        String externalId
) {

    public RegisterChildRequest {
        name = (name == null) ? null : name.strip();
        externalId = (externalId == null) ? null : externalId.strip();
    }

    @JsonIgnore
    @AssertTrue
    public boolean isBirthDateValid() {
        if (birthDate == null) {
            return true;
        }
        try {
            return !LocalDate.parse(birthDate).isAfter(LocalDate.now());
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    public LocalDate birthDateValue() {
        return LocalDate.parse(birthDate);
    }
}
