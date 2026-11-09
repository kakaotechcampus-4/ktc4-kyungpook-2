package com.itda.backend.dto.response.invitation;

import java.time.OffsetDateTime;

public record IssuedInvitationResponse(String id, String code, OffsetDateTime expiresAt) {
}
