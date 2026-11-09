package com.itda.backend.dto.response.consent;

import java.util.List;

import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.dto.response.organization.InstitutionResponse;

public record ConsentPreviewResponse(
        PreviewChild child,
        InstitutionResponse institution,
        String documentUrl,
        List<ConsentField> allowedFields) {

    public record PreviewChild(String id, String name, String birthDate) {
    }
}
