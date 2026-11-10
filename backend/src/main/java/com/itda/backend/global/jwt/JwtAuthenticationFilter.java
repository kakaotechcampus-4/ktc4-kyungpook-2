package com.itda.backend.global.jwt;

import com.itda.backend.global.exception.CommonErrorCode;
import com.itda.backend.global.security.ApiPaths;
import com.itda.backend.global.security.JsonErrorResponseWriter;
import com.itda.backend.service.user.RevokedSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final JwtCookie jwtCookie;
    private final RevokedSessionService revokedSessionService;
    private final JsonErrorResponseWriter jsonErrorResponseWriter;

    public JwtAuthenticationFilter(
            JwtProvider jwtProvider,
            JwtCookie jwtCookie,
            RevokedSessionService revokedSessionService,
            JsonErrorResponseWriter jsonErrorResponseWriter
    ) {
        this.jwtProvider = jwtProvider;
        this.jwtCookie = jwtCookie;
        this.revokedSessionService = revokedSessionService;
        this.jsonErrorResponseWriter = jsonErrorResponseWriter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String path = ApiPaths.pathOf(request);
        Optional<AccessToken> parsed = ApiPaths.AUTH_LOGOUT.equals(path)
                ? Optional.empty()
                : readAccessToken(request);

        if (parsed.isPresent()) {
            AccessToken token = parsed.get();
            boolean revoked;
            try {
                revoked = token.sessionId() != null && revokedSessionService.isRevoked(token.sessionId());
            } catch (RuntimeException e) {
                log.error("로그아웃 기록 확인 중 서버 오류가 발생했습니다.", e);
                jsonErrorResponseWriter.write(response, CommonErrorCode.INTERNAL_SERVER_ERROR);
                return;
            }
            if (!revoked) {
                var authentication = new UsernamePasswordAuthenticationToken(token.subject(), null, List.of());
                SecurityContextHolder.getContext().setAuthentication(authentication);
                if (path.startsWith(ApiPaths.API_PREFIX) && !ApiPaths.DEV_LOGIN.equals(path)) {
                    jwtProvider.renew(token).ifPresent(issued -> response.addHeader(
                            HttpHeaders.SET_COOKIE, jwtCookie.issueRenewal(
                                    token.sessionId(), issued.token(), issued.maxAge()).toString()));
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private Optional<AccessToken> readAccessToken(HttpServletRequest request) {
        String original = jwtCookie.read(request);
        return jwtProvider.parseForLogout(original).flatMap(session ->
                jwtProvider.parse(jwtCookie.readRenewal(request, session.sessionId()))
                        .filter(renewed -> session.sessionId().equals(renewed.sessionId())
                                && session.subject().equals(renewed.subject())
                                && session.loginAt().equals(renewed.loginAt()))
                        .or(() -> jwtProvider.parse(original)));
    }
}
