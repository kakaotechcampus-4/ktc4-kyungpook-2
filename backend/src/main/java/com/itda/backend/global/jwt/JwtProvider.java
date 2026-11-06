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

/**
 * 출입증(JWT)을 만들고 읽고 연장한다.
 *
 * <p>로그인할 때 로그인 번호({@code sid})와 처음 로그인한 시각({@code auth_time})을 담는다 — 둘 다 OpenID Connect 와
 * 같은 이름 · 같은 뜻이다. 연장해도 둘은 그대로 옮긴다. 로그아웃은 로그인 번호로 그 로그인의 출입증을 모두 무효로 하고,
 * 최대 로그인 시간은 처음 로그인한 시각부터 잰다.
 *
 * <p>발급과 읽기가 같은 {@link Clock} 을 쓴다. 읽기만 실제 시계로 만료를 보면 고정 시계로 잰 테스트와 실제 동작이 어긋난다.
 */
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

    /** 로그인. 새 로그인 번호를 받고, 지금이 처음 로그인한 시각이 된다. */
    public String createToken(String subject) {
        Instant now = now();
        return build(subject, UUID.randomUUID().toString(), now, now, now.plus(accessTokenTtl));
    }

    /** 서명이 맞고 만료되지 않았으면 그 내용. 아니면 빈 값이다. */
    public Optional<AccessToken> parse(String token) {
        return read(token, false);
    }

    /**
     * 로그아웃할 로그인을 읽는다. 서명이 맞으면 출입증 자체가 만료됐어도 읽는다 — 만료 직전에 발급한 연장 쿠키가
     * 아직 도착 중일 수 있다. 최대 로그인 시간이 지났으면 그 로그인의 출입증은 더 쓸 수 없어 기록하지 않는다.
     * 인증에는 만료를 검사하는 {@link #parse(String)} 를 사용한다.
     */
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
                // JJWT 는 서명을 확인한 뒤 만료 예외를 던진다. 서명 실패 등 다른 오류는 아래에서 거절한다.
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

    /**
     * 남은 시간이 유효시간의 절반보다 적으면 새 출입증을 만든다. 로그인 번호 · 처음 로그인한 시각은 그대로이고,
     * 만료는 "지금 + 유효시간" 과 "처음 로그인 + 최대 로그인 시간" 중 빠른 쪽이다.
     *
     * <p>로그인 번호가 없는 옛 출입증은 연장하지 않는다. 로그아웃으로 무효로 할 번호가 없어, 연장하면 로그아웃 뒤
     * 늦게 도착한 연장 쿠키가 로그인을 되살려도 막을 수 없다. 이미 최대 로그인 시간 끝에 맞춰 둔 출입증도 더 늘릴 수
     * 없으니 만들지 않는다.
     */
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

    /** JWT 시각은 초 단위라 맞춰 둔다 — 쿠키 만료와 출입증 만료가 같은 초를 가리키게. */
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
