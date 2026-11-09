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

import com.itda.backend.exception.user.UserErrorCode;
import com.itda.backend.exception.user.UserException;
import com.itda.backend.global.exception.CommonErrorCode;
import com.itda.backend.service.user.AccountAccess;
import com.itda.backend.service.user.UserService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SignupCompletionFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED_BEFORE_SIGNUP = Set.of(
            ApiPaths.AUTH_ME,
            ApiPaths.AUTH_SIGNUP,
            ApiPaths.AUTH_LOGOUT,
            ApiPaths.DEV_LOGIN,
            ApiPaths.HEALTH,
            ApiPaths.TERMS);

    private static final Set<String> ALLOWED_BEFORE_TERMS = Set.of(ApiPaths.AUTH_TERMS);

    private final UserService userService;
    private final JsonErrorResponseWriter jsonErrorResponseWriter;

    public SignupCompletionFilter(UserService userService, JsonErrorResponseWriter jsonErrorResponseWriter) {
        this.userService = userService;
        this.jsonErrorResponseWriter = jsonErrorResponseWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = ApiPaths.pathOf(request);
        return !path.startsWith(ApiPaths.API_PREFIX) || ALLOWED_BEFORE_SIGNUP.contains(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof String userId) {
            try {
                AccountAccess access = userService.accessOf(userId);
                if (access == AccountAccess.WITHDRAWAL_IN_PROGRESS) {
                    jsonErrorResponseWriter.write(response, UserErrorCode.WITHDRAWAL_IN_PROGRESS);
                    return;
                }
                if (access == AccountAccess.SIGNUP_NOT_COMPLETED) {
                    jsonErrorResponseWriter.write(response, UserErrorCode.SIGNUP_NOT_COMPLETED);
                    return;
                }
                if (access == AccountAccess.TERMS_NOT_AGREED && !ALLOWED_BEFORE_TERMS.contains(ApiPaths.pathOf(request))) {
                    jsonErrorResponseWriter.write(response, UserErrorCode.TERMS_NOT_AGREED);
                    return;
                }
            } catch (UserException e) {
                SecurityContextHolder.clearContext();
                jsonErrorResponseWriter.write(response, e.getErrorCode());
                return;
            } catch (RuntimeException e) {
                log.error("가입 · 약관 동의 상태 확인 중 서버 오류가 발생했습니다.", e);
                jsonErrorResponseWriter.write(response, CommonErrorCode.INTERNAL_SERVER_ERROR);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
