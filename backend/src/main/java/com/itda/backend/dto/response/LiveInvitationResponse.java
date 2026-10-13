package com.itda.backend.dto.response;

import java.time.OffsetDateTime;

/** O-16 아직 쓸 수 있는 초대 코드 하나. */
public record LiveInvitationResponse(String id, String code, OffsetDateTime issuedAt, OffsetDateTime expiresAt) {
}
