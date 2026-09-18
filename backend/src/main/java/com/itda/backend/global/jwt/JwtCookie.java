package com.itda.backend.global.jwt;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

import java.time.Duration;

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

    private static final String PATH = "/";
    private static final String SAME_SITE = "Lax";

    private final boolean secure;
    private final Duration ttl;

    public JwtCookie(
            @Value("${app.auth.cookie.secure:false}") boolean secure,
            @Value("${jwt.access-token-expiration-ms:3600000}") long expirationMs
    ) {
        this.secure = secure;
        this.ttl = Duration.ofMillis(expirationMs);
    }

    /** 로그인 성공 직후. 만료를 토큰 유효시간과 맞춰 둔다. */
    public ResponseCookie issue(String token) {
        return base(token).maxAge(ttl).build();
    }

    /**
     * 로그아웃. httpOnly 라 프론트가 스스로 지울 수 없으므로, 서버가 지워주지 않으면
     * 사용자는 로그아웃할 방법이 없다.
     */
    public ResponseCookie expire() {
        return base("").maxAge(0).build();
    }

    public String read(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, NAME);
        return cookie == null ? null : cookie.getValue();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(SAME_SITE)
                .path(PATH);
    }
}
