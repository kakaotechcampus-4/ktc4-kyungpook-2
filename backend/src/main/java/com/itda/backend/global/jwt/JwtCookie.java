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

/**
 * 출입증(JWT)을 담는 httpOnly 쿠키.
 *
 * <p>예전에는 JWT 를 응답 본문으로 내려주고 프론트가 localStorage 에 보관했다. 이제는
 * 카카오가 백엔드로 돌아오고 백엔드가 프론트로 리다이렉트하는 구조라, 응답 본문을 쓸 수 없다.
 * 페이지 이동에 실을 수 있는 건 주소와 쿠키뿐인데, 주소에 실으면 토큰이 브라우저 히스토리와
 * nginx 접근 로그에 평문으로 남는다. 그래서 쿠키다.
 *
 * <p>덤으로 보안이 올라간다. {@code httpOnly} 라 브라우저 JS 가 읽지 못해, XSS 로도
 * 토큰을 훔칠 수 없다. localStorage 는 XSS 한 번이면 통째로 털렸다.
 *
 * <p>대신 쿠키는 브라우저가 자동으로 보내므로 CSRF 대비가 필요해진다.
 * {@code SameSite=Lax} 가 교차 사이트 POST 에 쿠키를 싣지 않고,
 * 그 위에 SecurityConfig 가 CSRF 토큰 검사를 건다.
 */
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

    /**
     * 로그인할 때만 원본을 심는다. 원본 JWT 는 3시간 뒤 만료돼도, 어느 로그인의 갱신 쿠키를 읽을지 선택하려고
     * 최대 로그인 시간까지 보관한다. 인증에는 별도로 JWT 만료를 검사한다.
     */
    public ResponseCookie issue(String token) {
        return base(NAME, token).maxAge(maxSession).build();
    }

    /** 로그인 응답에 실을 쿠키 전부. 이전 로그인의 갱신 쿠키를 지우고 새 원본을 심는다 — 카카오 로그인과 개발용 로그인이 함께 쓴다. */
    public List<ResponseCookie> login(HttpServletRequest request, String token) {
        List<ResponseCookie> cookies = new ArrayList<>(expireRenewals(request));
        cookies.add(issue(token));
        return cookies;
    }

    /** 늦게 도착한 이전 로그인의 응답이 새 로그인을 덮지 않도록 로그인 번호마다 다른 이름을 쓴다. */
    public ResponseCookie issueRenewal(String sessionId, String token, Duration maxAge) {
        return base(RENEWAL_PREFIX + sessionId, token).maxAge(maxAge).build();
    }

    /**
     * 로그아웃. httpOnly 라 프론트가 스스로 지울 수 없으므로, 서버가 지워주지 않으면
     * 사용자는 로그아웃할 방법이 없다.
     */
    public ResponseCookie expire() {
        return base(NAME, "").maxAge(0).build();
    }

    public String read(HttpServletRequest request) {
        return read(request, NAME);
    }

    public String readRenewal(HttpServletRequest request, String sessionId) {
        return sessionId == null ? null : read(request, RENEWAL_PREFIX + sessionId);
    }

    /** 계정을 반복 전환해도 이전 갱신 쿠키가 쌓이지 않도록 로그인·로그아웃 때 요청에 실린 것만 지운다. */
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
