package com.itda.backend.dto.request.consent;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.itda.backend.domain.consent.ConsentAction;
import com.itda.backend.domain.consent.ConsentField;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

public record UpdateConsentRequest(
        @Schema(example = "grant", allowableValues = {"grant", "revoke"})
        @NotNull
        ConsentAction action,

        @ArraySchema(
                arraySchema = @Schema(description = "공유를 허락할 항목. grant 에서 필수(1개 이상), revoke 에서는 무시한다"),
                schema = @Schema(allowableValues = {"daily_summary", "weekly_insight"}))
        List<@NotNull ConsentField> allowedFields
) {

    @JsonIgnore
    @AssertTrue
    public boolean isAllowedFieldsValidForAction() {
        if (action != ConsentAction.GRANT) {
            return true;
        }
        return allowedFields != null && !allowedFields.isEmpty();
    }
}
