package com.itda.backend.global.security;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.itda.backend.exception.UserErrorCode;
import com.itda.backend.service.UserService;

/**
 * 가입을 마치지 않은(역할이 없는) 회원을 API 에서 막는다 → 403 SIGNUP_NOT_COMPLETED.
 *
 * <p>보호 API 마다 확인을 넣지 않고 여기 한 곳에서 막는다. 새 API 가 생겨도 따로 챙기지 않아도 된다.
 * 대가로 로그인한 사용자의 /api 요청마다 회원 조회가 한 번 더 일어난다(PK 조회).
 *
 * <p>가입 화면에 필요한 세 경로 — 상태 조회(/auth/me), 가입(/auth/signup), 로그아웃 — 와
 * 헬스 체크만 통과시킨다.
 *
 * <p>{@code @Component} 로 두지 않는다. 그러면 Spring Boot 가 서블릿 필터로도 등록해 보안 체인 밖에서
 * 한 번 더 돈다. SecurityConfig 가 직접 만들어 JWT 필터 바로 뒤에 넣는다.
 */
public class SignupCompletionFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/api/";

    private static final Set<String> ALLOWED_BEFORE_SIGNUP = Set.of(
            "/api/v1/auth/me",
            "/api/v1/auth/signup",
            "/api/v1/auth/logout",
            "/api/health");

    private final UserService userService;
    private final JsonErrorResponseWriter jsonErrorResponseWriter;

    public SignupCompletionFilter(UserService userService, JsonErrorResponseWriter jsonErrorResponseWriter) {
        this.userService = userService;
        this.jsonErrorResponseWriter = jsonErrorResponseWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith(API_PREFIX) || ALLOWED_BEFORE_SIGNUP.contains(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        // 비로그인 요청은 여기서 판단하지 않는다. 뒤의 인가 단계가 401 로 끝낸다.
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof String userId
                && !userService.isSignupCompleted(userId)) {
            jsonErrorResponseWriter.write(response, UserErrorCode.SIGNUP_NOT_COMPLETED);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
