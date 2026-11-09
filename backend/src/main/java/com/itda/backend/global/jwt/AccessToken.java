package com.itda.backend.global.jwt;

import java.time.Instant;

public record AccessToken(String subject, String sessionId, Instant loginAt, Instant expiresAt) {
}
