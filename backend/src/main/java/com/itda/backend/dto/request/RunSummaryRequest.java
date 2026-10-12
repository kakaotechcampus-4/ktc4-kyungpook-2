package com.itda.backend.dto.request;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * POST /api/v1/summaries/run 의 요청. 교사가 자기 기관 아이의 그 날짜 묶음을 마감 전에 요약하도록 요청한다.
 *
 * <p>{@code entryDate} 를 문자열로 받는 이유는 {@link RegisterChildRequest} 와 같다 — "yyyy-MM-dd" 이고 실제로 있는
 * 날짜만 받는다. 실패는 전부 400 INVALID_REQUEST 다.
 */
public record RunSummaryRequest(
        @NotNull
        Long childId,

        @Schema(type = "string", format = "date", example = "2026-10-08")
        @NotNull
        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$")
        String entryDate
) {

    /** 실제로 있는 날짜여야 한다. null 은 {@code @NotNull} 이 따로 잡는다. */
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

    /** 검증을 통과한 뒤에만 부른다. */
    public LocalDate entryDateValue() {
        return LocalDate.parse(entryDate);
    }
}
