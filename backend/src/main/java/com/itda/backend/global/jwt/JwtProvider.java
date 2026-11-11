package com.itda.backend.global.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Component
public class JwtProvider {

    static final String SESSION_ID = "sid";
    static final String AUTH_TIME = "auth_time";

    private final SecretKey key;
    private final Duration accessTokenTtl;
    private final Duration maxSession;
    private final Clock clock;
    private final JwtParser parser;

    public JwtProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiration-ms:10800000}") long accessTokenExpirationMs,
            @Value("${jwt.max-session-ms:43200000}") long maxSessionMs,
            Clock clock
    ) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtl = Duration.ofMillis(accessTokenExpirationMs);
        this.maxSession = Duration.ofMillis(maxSessionMs);
        this.clock = clock;
        this.parser = Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(clock.instant()))
                .build();
    }

    public String createToken(String subject) {
        Instant now = now();
        return build(subject, UUID.randomUUID().toString(), now, now, now.plus(accessTokenTtl));
    }

    public Optional<AccessToken> parse(String token) {
        return read(token, false);
    }

    public Optional<AccessToken> parseForLogout(String token) {
        return read(token, true)
                .filter(parsed -> now().isBefore(parsed.loginAt().plus(maxSession)));
    }

    private Optional<AccessToken> read(String token, boolean allowExpired) {
        if (!StringUtils.hasText(token)) {
            return Optional.empty();
        }
        try {
            Claims claims;
            try {
                claims = parser.parseSignedClaims(token).getPayload();
            } catch (ExpiredJwtException e) {
                if (!allowExpired) {
                    return Optional.empty();
                }
                claims = e.getClaims();
            }
            Number authTime = claims.get(AUTH_TIME, Number.class);
            Instant loginAt = authTime != null
                    ? Instant.ofEpochSecond(authTime.longValue())
                    : claims.getIssuedAt().toInstant();
            return Optional.of(new AccessToken(
                    claims.getSubject(),
                    claims.get(SESSION_ID, String.class),
                    loginAt,
                    claims.getExpiration().toInstant()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public Optional<IssuedToken> renew(AccessToken token) {
        if (token.sessionId() == null) {
            return Optional.empty();
        }
        Instant now = now();
        if (Duration.between(now, token.expiresAt()).compareTo(accessTokenTtl.dividedBy(2)) >= 0) {
            return Optional.empty();
        }
        Instant sessionEnd = token.loginAt().plus(maxSession);
        Instant expiresAt = now.plus(accessTokenTtl);
        if (expiresAt.isAfter(sessionEnd)) {
            expiresAt = sessionEnd;
        }
        if (!expiresAt.isAfter(token.expiresAt())) {
            return Optional.empty();
        }
        String renewed = build(token.subject(), token.sessionId(), token.loginAt(), now, expiresAt);
        return Optional.of(new IssuedToken(renewed, Duration.between(now, expiresAt)));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    private String build(String subject, String sessionId, Instant loginAt, Instant issuedAt, Instant expiresAt) {
        return Jwts.builder()
                .subject(subject)
                .claim(SESSION_ID, sessionId)
                .claim(AUTH_TIME, loginAt.getEpochSecond())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }
}
