package com.itda.backend.dto.request.insight;

import java.util.Locale;

import com.itda.backend.domain.approval.Gate2Action;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record Gate2DecisionRequest(
        @Schema(allowableValues = {"approve", "hold"}, example = "approve")
        @NotNull
        @Pattern(regexp = "^(approve|hold)$")
        String decision
) {

    public Gate2Action action() {
        return Gate2Action.valueOf(decision.toUpperCase(Locale.ROOT));
    }
}
