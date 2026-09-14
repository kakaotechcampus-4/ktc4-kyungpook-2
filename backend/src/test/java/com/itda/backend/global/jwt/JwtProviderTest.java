package com.itda.backend.global.jwt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtProviderTest {

    private final JwtProvider jwtProvider =
            new JwtProvider("test-secret-key-for-jwt-unit-test-1234", 1000 * 60);

    @Test
    void createsTokenThatCanBeParsedBackToSameSubject() {
        String token = jwtProvider.createToken("12345");

        assertThat(jwtProvider.getSubject(token)).isEqualTo("12345");
        assertThat(jwtProvider.validateToken(token)).isTrue();
    }

    @Test
    void invalidTokenFailsValidation() {
        assertThat(jwtProvider.validateToken("not-a-real-token")).isFalse();
    }
}
