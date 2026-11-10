package com.itda.backend.global.jwt;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class JwtProviderTest {

    private static final String SECRET = "test-secret-key-for-jwt-unit-test-1234";
    private static final long TTL_MS = Duration.ofHours(3).toMillis();
    private static final long MAX_SESSION_MS = Duration.ofHours(12).toMillis();
    private static final Instant LOGIN = Instant.parse("2026-11-06T00:00:00Z");

    private static JwtProvider at(Instant now) {
        return new JwtProvider(SECRET, TTL_MS, MAX_SESSION_MS, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static AccessToken loginThenReadAt(Instant later) {
        String token = at(LOGIN).createToken("12345");
        return at(later).parse(token).orElseThrow();
    }

    @Test
    void createsTokenWithSubjectSessionIdAndLoginTime() {
        AccessToken token = at(LOGIN).parse(at(LOGIN).createToken("12345")).orElseThrow();

        assertThat(token.subject()).isEqualTo("12345");
        assertThat(token.sessionId()).isNotBlank();
        assertThat(token.loginAt()).isEqualTo(LOGIN);
        assertThat(token.expiresAt()).isEqualTo(LOGIN.plus(Duration.ofHours(3)));
    }

    @Test
    void everyLoginGetsItsOwnSessionId() {
        JwtProvider provider = at(LOGIN);

        String first = provider.parse(provider.createToken("12345")).orElseThrow().sessionId();
        String second = provider.parse(provider.createToken("12345")).orElseThrow().sessionId();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void invalidTokenIsNotParsed() {
        assertThat(at(LOGIN).parse("not-a-real-token")).isEmpty();
        assertThat(at(LOGIN).parse("")).isEmpty();
        assertThat(at(LOGIN).parse(null)).isEmpty();
    }

    @Test
    void tokenSignedWithAnotherKeyIsNotParsed() {
        String forged = new JwtProvider("another-secret-key-for-jwt-unit-test-99", TTL_MS, MAX_SESSION_MS,
                Clock.fixed(LOGIN, ZoneOffset.UTC)).createToken("12345");

        assertThat(at(LOGIN).parse(forged)).isEmpty();
    }

    @Test
    void readingUsesTheInjectedClockForExpiry() {
        String token = at(LOGIN).createToken("12345");

        assertThat(at(LOGIN.plus(Duration.ofHours(3)).minusSeconds(1)).parse(token)).isPresent();
        assertThat(at(LOGIN.plus(Duration.ofHours(3)).plusSeconds(1)).parse(token)).isEmpty();
    }

    @Test
    void logoutReadsExpiredTokenWithoutAuthenticatingIt() {
        String token = at(LOGIN).createToken("12345");
        JwtProvider afterExpiry = at(LOGIN.plus(Duration.ofHours(3)).plusSeconds(1));

        assertThat(afterExpiry.parse(token)).isEmpty();
        assertThat(afterExpiry.parseForLogout(token)).contains(at(LOGIN).parse(token).orElseThrow());
    }

    @Test
    void logoutOnlyReadsTokensBeforeTheMaximumSessionEnds() {
        String token = at(LOGIN).createToken("12345");
        Instant sessionEnd = LOGIN.plus(Duration.ofMillis(MAX_SESSION_MS));

        assertThat(at(sessionEnd.minusSeconds(1)).parseForLogout(token)).isPresent();
        assertThat(at(sessionEnd).parseForLogout(token)).isEmpty();
        assertThat(at(sessionEnd.plusSeconds(1)).parseForLogout(token)).isEmpty();
    }

    @Test
    void logoutRejectsExpiredTokenSignedWithAnotherKey() {
        String forged = new JwtProvider("another-secret-key-for-jwt-unit-test-99", TTL_MS, MAX_SESSION_MS,
                Clock.fixed(LOGIN, ZoneOffset.UTC)).createToken("12345");

        assertThat(at(LOGIN.plus(Duration.ofHours(3)).plusSeconds(1)).parseForLogout(forged)).isEmpty();
    }

    @Test
    void logoutRejectsInvalidTokens() {
        assertThat(at(LOGIN).parseForLogout("not-a-real-token")).isEmpty();
        assertThat(at(LOGIN).parseForLogout("")).isEmpty();
        assertThat(at(LOGIN).parseForLogout(null)).isEmpty();
    }

    @Test
    void doesNotRenewWhileMoreThanHalfRemains() {
        Instant now = LOGIN.plus(Duration.ofMinutes(89));

        assertThat(at(now).renew(loginThenReadAt(now))).isEmpty();
    }

    @Test
    void renewsWhenLessThanHalfRemainsKeepingSessionAndLoginTime() {
        Instant now = LOGIN.plus(Duration.ofMinutes(91));
        AccessToken old = loginThenReadAt(now);

        IssuedToken issued = at(now).renew(old).orElseThrow();
        AccessToken renewed = at(now).parse(issued.token()).orElseThrow();

        assertThat(issued.maxAge()).isEqualTo(Duration.ofHours(3));
        assertThat(renewed.expiresAt()).isEqualTo(now.plus(Duration.ofHours(3)));
        assertThat(renewed.subject()).isEqualTo(old.subject());
        assertThat(renewed.sessionId()).isEqualTo(old.sessionId());
        assertThat(renewed.loginAt()).isEqualTo(LOGIN);
    }

    @Test
    void renewalIsCappedAtTheMaximumSession() {
        AccessToken eightHoursIn = renewedEveryTwoHoursUntil(Duration.ofHours(8));
        Instant now = LOGIN.plus(Duration.ofHours(10));

        IssuedToken issued = at(now).renew(eightHoursIn).orElseThrow();

        assertThat(issued.maxAge()).isEqualTo(Duration.ofHours(2));
        assertThat(at(now).parse(issued.token()).orElseThrow().expiresAt())
                .isEqualTo(LOGIN.plus(Duration.ofHours(12)));
    }

    @Test
    void doesNotRenewOnceCappedAtTheMaximumSession() {
        Instant tenHoursIn = LOGIN.plus(Duration.ofHours(10));
        String capped = at(tenHoursIn).renew(renewedEveryTwoHoursUntil(Duration.ofHours(8))).orElseThrow().token();
        Instant elevenHoursIn = LOGIN.plus(Duration.ofHours(11));

        assertThat(at(elevenHoursIn).renew(at(elevenHoursIn).parse(capped).orElseThrow())).isEmpty();
        assertThat(at(LOGIN.plus(Duration.ofHours(12)).plusSeconds(1)).parse(capped)).isEmpty();
    }

    @Test
    void legacyTokenWithoutSessionIdIsAcceptedButNotRenewed() {
        String legacy = Jwts.builder()
                .subject("12345")
                .issuedAt(Date.from(LOGIN))
                .expiration(Date.from(LOGIN.plus(Duration.ofHours(3))))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
        Instant now = LOGIN.plus(Duration.ofHours(2));

        AccessToken token = at(now).parse(legacy).orElseThrow();

        assertThat(token.sessionId()).isNull();
        assertThat(token.loginAt()).isEqualTo(LOGIN);
        assertThat(at(now).renew(token)).isEmpty();
    }

    private static AccessToken renewedEveryTwoHoursUntil(Duration until) {
        String token = at(LOGIN).createToken("12345");
        for (Duration elapsed = Duration.ofHours(2); elapsed.compareTo(until) <= 0; elapsed = elapsed.plusHours(2)) {
            Instant now = LOGIN.plus(elapsed);
            token = at(now).renew(at(now).parse(token).orElseThrow()).orElseThrow().token();
        }
        return at(LOGIN.plus(until)).parse(token).orElseThrow();
    }
}
