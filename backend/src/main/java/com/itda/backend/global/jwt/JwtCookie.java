package com.itda.backend.global.jwt;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
public class JwtCookie {

    public static final String NAME = "access_token";
    public static final String RENEWAL_PREFIX = NAME + "_renewal_";

    private static final String PATH = "/";
    private static final String SAME_SITE = "Lax";

    private final boolean secure;
    private final Duration maxSession;

    public JwtCookie(
            @Value("${app.auth.cookie.secure:false}") boolean secure,
            @Value("${jwt.max-session-ms:43200000}") long maxSessionMs
    ) {
        this.secure = secure;
        this.maxSession = Duration.ofMillis(maxSessionMs);
    }

    public ResponseCookie issue(String token) {
        return base(NAME, token).maxAge(maxSession).build();
    }

    public List<ResponseCookie> login(HttpServletRequest request, String token) {
        List<ResponseCookie> cookies = new ArrayList<>(expireRenewals(request));
        cookies.add(issue(token));
        return cookies;
    }

    public ResponseCookie issueRenewal(String sessionId, String token, Duration maxAge) {
        return base(RENEWAL_PREFIX + sessionId, token).maxAge(maxAge).build();
    }

    public ResponseCookie expire() {
        return base(NAME, "").maxAge(0).build();
    }

    public String read(HttpServletRequest request) {
        return read(request, NAME);
    }

    public String readRenewal(HttpServletRequest request, String sessionId) {
        return sessionId == null ? null : read(request, RENEWAL_PREFIX + sessionId);
    }

    public List<ResponseCookie> expireRenewals(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return List.of();
        }
        return Arrays.stream(cookies)
                .map(Cookie::getName)
                .filter(name -> name.startsWith(RENEWAL_PREFIX))
                .distinct()
                .map(name -> base(name, "").maxAge(0).build())
                .toList();
    }

    private String read(HttpServletRequest request, String name) {
        Cookie cookie = WebUtils.getCookie(request, name);
        return cookie == null ? null : cookie.getValue();
    }

    private ResponseCookie.ResponseCookieBuilder base(String name, String value) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(SAME_SITE)
                .path(PATH);
    }
}
