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

/**
 * 매 요청에서 출입증을 꺼내 인증을 세우고, 쓰는 동안 출입증을 연장한다.
 *
 * <p>예전에는 {@code Authorization: Bearer} 헤더를 읽었다. 프론트가 localStorage 에서
 * 토큰을 꺼내 직접 붙여줬기 때문이다. 이제 토큰은 httpOnly 쿠키에 있고 브라우저가 알아서
 * 보내므로, 프론트는 아무것도 붙이지 않는다.
 *
 * <p><b>principal 은 내부 userId 문자열이다.</b> 예전에는 카카오 회원번호였지만,
 * 이제 subject 가 userId 라 그대로 심으면 된다. 사용자를 실제로 찾아 역할·소속을 판단하는 일은
 * 뒤의 가입 확인 필터와 Service 계층 몫이다. ({@code @AuthenticationPrincipal String userId} 로 받는다.)
 *
 * <p><b>로그아웃한 로그인은 받지 않는다.</b> 출입증의 로그인 번호가 로그아웃 기록에 있으면 인증을 세우지 않아 뒤에서
 * 401 이 된다. 이때 만료 쿠키를 내려주지 않는다 — 이 응답이 늦게 도착하면 그 사이 새로 로그인한 쿠키를 지운다.
 *
 * <p><b>자동 연장.</b> 남은 시간이 유효시간의 절반보다 적은 {@code /api/} 요청에는 응답에 새 출입증 쿠키를 싣는다
 * (만료는 최대 로그인 시간을 넘지 않는다, {@link JwtProvider#renew}). 브라우저가 알아서 바꿔 끼워 프론트는 할 일이 없다.
 * 로그아웃과 개발용 로그인은 연장하지 않는다. 그 응답도 출입증 쿠키를 싣는데(만료 쿠키 · 새 로그인 쿠키) 연장 쿠키가
 * 한 응답에 같이 실리면 어느 쪽이 남을지 순서에 달린다. 로그아웃은 아예 출입증을 보지 않는다(아래 본문 주석).
 *
 * <p>{@code @Component} 로 두지 않는다. 그러면 Spring Boot 가 서블릿 필터로도 등록해 보안 체인 밖에서
 * 한 번 더 돌고, 컨트롤러 단위 테스트마다 이 필터의 의존성을 채워야 한다. SecurityConfig 가 직접 만든다.
 */
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
        // 로그아웃은 인증 없이 열려 있고 컨트롤러가 출입증을 직접 읽어 기록한다. 여기서 로그아웃 기록을 조회하다
        // DB 장애로 끝나면 쿠키를 지우지 못하므로 보지 않고 지나간다
        Optional<AccessToken> parsed = ApiPaths.AUTH_LOGOUT.equals(path)
                ? Optional.empty()
                : jwtProvider.parse(jwtCookie.read(request));

        if (parsed.isPresent()) {
            AccessToken token = parsed.get();
            boolean revoked;
            try {
                revoked = token.sessionId() != null && revokedSessionService.isRevoked(token.sessionId());
            } catch (RuntimeException e) {
                // 필터 예외를 /error 로 넘기면 ERROR 디스패치의 인증 실패가 401 로 바꿀 수 있다 — 장애를 로그아웃으로 보이게 하지 않는다
                log.error("로그아웃 기록 확인 중 서버 오류가 발생했습니다.", e);
                jsonErrorResponseWriter.write(response, CommonErrorCode.INTERNAL_SERVER_ERROR);
                return;
            }
            if (!revoked) {
                var authentication = new UsernamePasswordAuthenticationToken(token.subject(), null, List.of());
                SecurityContextHolder.getContext().setAuthentication(authentication);
                if (path.startsWith(ApiPaths.API_PREFIX) && !ApiPaths.DEV_LOGIN.equals(path)) {
                    jwtProvider.renew(token).ifPresent(issued -> response.addHeader(
                            HttpHeaders.SET_COOKIE, jwtCookie.issue(issued.token(), issued.maxAge()).toString()));
                }
            }
        }

        filterChain.doFilter(request, response);
    }
}
