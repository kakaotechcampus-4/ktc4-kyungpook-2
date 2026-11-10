package com.itda.backend.dto.response.child;

import java.time.OffsetDateTime;

import com.itda.backend.dto.response.organization.InstitutionResponse;

public record InstitutionLinkRequestResponse(InstitutionResponse institution, String status, OffsetDateTime requestedAt) {

    public static final String PENDING = "pending";
}
