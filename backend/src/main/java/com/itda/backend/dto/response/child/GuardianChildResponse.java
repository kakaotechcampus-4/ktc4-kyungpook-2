package com.itda.backend.dto.response.child;

import java.time.OffsetDateTime;
import java.util.List;

import com.itda.backend.domain.child.ChildStatus;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.dto.response.organization.InstitutionResponse;

public record GuardianChildResponse(
        String id,
        String name,
        String birthDate,
        ChildStatus status,
        List<ChildInstitution> institutions,
        List<LinkRequest> linkRequests,
        ChildCareInfoResponse care,
        boolean hasUnreadInsight) {

    public record ChildInstitution(InstitutionResponse institution, ConsentState consent) {
    }

    public record LinkRequest(InstitutionResponse institution, OffsetDateTime requestedAt) {
    }
}
