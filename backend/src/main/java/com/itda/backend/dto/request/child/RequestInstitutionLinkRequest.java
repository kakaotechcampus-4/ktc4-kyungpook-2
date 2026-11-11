package com.itda.backend.dto.request.child;

import jakarta.validation.constraints.NotBlank;

import io.swagger.v3.oas.annotations.media.Schema;

public record RequestInstitutionLinkRequest(
        @Schema(example = "ABCD-EFGH")
        @NotBlank
        String code
) {
}
