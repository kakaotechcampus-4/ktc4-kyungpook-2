package com.itda.backend.global.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 매 요청에서 출입증을 꺼내 인증을 세운다.
 *
 * <p>예전에는 {@code Authorization: Bearer} 헤더를 읽었다. 프론트가 localStorage 에서
 * 토큰을 꺼내 직접 붙여줬기 때문이다. 이제 토큰은 httpOnly 쿠키에 있고 브라우저가 알아서
 * 보내므로, 프론트는 아무것도 붙이지 않는다.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final JwtCookie jwtCookie;

    public JwtAuthenticationFilter(JwtProvider jwtProvider, JwtCookie jwtCookie) {
        this.jwtProvider = jwtProvider;
        this.jwtCookie = jwtCookie;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String token = jwtCookie.read(request);

        if (StringUtils.hasText(token) && jwtProvider.validateToken(token)) {
            String subject = jwtProvider.getSubject(token);
            var authentication = new UsernamePasswordAuthenticationToken(subject, null, List.of());
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }
}
