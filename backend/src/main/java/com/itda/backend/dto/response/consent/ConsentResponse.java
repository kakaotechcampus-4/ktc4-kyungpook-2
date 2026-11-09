package com.itda.backend.dto.response.consent;

import java.util.List;

import com.itda.backend.domain.child.ChildStatus;
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.ConsentState;

public record ConsentResponse(ChildStatus childStatus, ConsentState consent, List<ConsentField> allowedFields) {
}
