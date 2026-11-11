package com.itda.backend.dto.response.child;

import com.itda.backend.domain.child.ChildStatus;
import com.itda.backend.domain.consent.ConsentState;

public record ChildRosterResponse(
        String id, String name, String birthDate, String externalId, ChildStatus status, ConsentState consent) {
}
