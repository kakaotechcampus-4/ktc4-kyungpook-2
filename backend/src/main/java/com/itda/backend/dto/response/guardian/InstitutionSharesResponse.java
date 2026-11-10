package com.itda.backend.dto.response.guardian;

import java.time.LocalDateTime;
import java.util.List;

import com.itda.backend.dto.response.organization.InstitutionResponse;

public record InstitutionSharesResponse(
        InstitutionResponse institution,
        List<SharedInsight> shares) {

    public record SharedInsight(
            String insightId,
            String period,
            InstitutionResponse from,
            LocalDateTime grantedAt,
            boolean visible,
            LocalDateTime hiddenAt) {
    }
}
