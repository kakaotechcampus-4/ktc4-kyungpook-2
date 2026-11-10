package com.itda.backend.global.jwt;

import java.time.Duration;

public record IssuedToken(String token, Duration maxAge) {
}
