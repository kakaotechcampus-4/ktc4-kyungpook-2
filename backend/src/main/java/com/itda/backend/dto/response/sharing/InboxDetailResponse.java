package com.itda.backend.dto.response.sharing;

import java.time.LocalDateTime;

import com.itda.backend.dto.response.organization.InstitutionResponse;

public record InboxDetailResponse(
        String id,
        String childId,
        String childName,
        String externalId,
        InstitutionResponse from,
        String period,
        LocalDateTime receivedAt,
        LocalDateTime readAt,
        String content) {
}
