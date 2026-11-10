package com.itda.backend.dto.response.invitation;

import java.time.OffsetDateTime;

public record LiveInvitationResponse(String id, String code, OffsetDateTime issuedAt, OffsetDateTime expiresAt) {
}
