package com.itda.backend.dto.response.child;

import com.itda.backend.dto.response.organization.InstitutionResponse;

public record GuardianJournalResponse(
        String id, String childId, InstitutionResponse institution, String date, boolean isNew, String content) {
}
