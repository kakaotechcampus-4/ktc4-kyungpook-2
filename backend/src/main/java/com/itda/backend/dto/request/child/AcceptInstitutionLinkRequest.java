package com.itda.backend.dto.request.child;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

public record AcceptInstitutionLinkRequest(
        @Schema(example = "2026-0031")
        @NotBlank
        @Size(max = 50)
        String externalId
) {

    public AcceptInstitutionLinkRequest {
        externalId = (externalId == null) ? null : externalId.strip();
    }
}
