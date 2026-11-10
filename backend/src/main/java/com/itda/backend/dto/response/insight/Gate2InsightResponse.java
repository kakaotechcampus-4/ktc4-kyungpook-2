package com.itda.backend.dto.response.insight;

import java.util.List;

import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.dto.response.organization.InstitutionResponse;

public record Gate2InsightResponse(
        String id,
        String childId,
        String childName,
        String period,
        String content,
        boolean needsReview,
        boolean teacherWritten,
        InstitutionResponse primarySource,
        List<EvidenceRef> evidence,
        List<Target> targets,
        String gate2Status) {

    public record EvidenceRef(String childContextId, String date, String label, InstitutionResponse institution,
            boolean lookedUp) {
    }

    public record Target(InstitutionResponse institution, ConsentState consent) {
    }
}
