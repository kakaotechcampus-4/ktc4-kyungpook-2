package com.itda.backend.dto.response.checklist;

import java.time.LocalDateTime;
import java.util.List;

import com.itda.backend.dto.response.organization.InstitutionResponse;

public record GuardianChecklistResponse(
        String id,
        InstitutionResponse institution,
        List<String> items,
        LocalDateTime sentAt,
        String status,
        LocalDateTime confirmedAt) {
}
