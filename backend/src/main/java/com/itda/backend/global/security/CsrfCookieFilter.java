package com.itda.backend.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * CSRF 토큰 쿠키를 실제로 내보낸다.
 *
 * <p>없으면 조용히 망가진다. {@code CookieCsrfTokenRepository} 는 토큰을 <b>게으르게</b>
 * 만든다 — 누군가 {@link CsrfToken#getToken()} 을 불러야 그때 값이 생기고 쿠키가 써진다.
 * 서버 렌더링 화면이라면 폼을 그리면서 자연히 불리지만, 우리는 JSON 만 내보내므로
 * 아무도 부르지 않는다. 그러면 {@code XSRF-TOKEN} 쿠키가 영영 나가지 않고,
 * 프론트는 보낼 토큰을 구할 방법이 없어 <b>모든 POST·PUT·DELETE 가 403</b> 이 된다.
 *
 * <p>이 필터가 매 요청마다 토큰을 한 번 건드려서 쿠키가 나가게 만든다.
 * Spring Security 문서가 SPA 에 권하는 방식이다.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            // 반환값은 쓰지 않는다. 부르는 행위 자체가 토큰을 만들고 쿠키를 쓰게 한다.
            csrfToken.getToken();
        }
        filterChain.doFilter(request, response);
    }
}
