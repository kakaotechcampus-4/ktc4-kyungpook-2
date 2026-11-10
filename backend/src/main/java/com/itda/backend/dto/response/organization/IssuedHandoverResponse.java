package com.itda.backend.dto.response.organization;

import java.time.OffsetDateTime;

public record IssuedHandoverResponse(String code, OffsetDateTime expiresAt) {
}
