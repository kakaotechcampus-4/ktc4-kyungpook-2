package com.itda.backend.global.security.oauth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * state 불일치, 사용자의 동의 취소, 카카오 장애 등 로그인이 실패한 모든 경로가 여기로 온다.
 *
 * <p>실패해도 사용자가 보는 건 브라우저 화면이므로 JSON 을 내려봐야 소용없다.
 * 프론트 로그인 화면으로 돌려보내고, 무엇이 틀렸는지는 짧은 코드로만 전달한다 —
 * 원인을 그대로 실어 보내면 공격자에게 탐색 힌트가 된다.
 */
@Slf4j
@Component
public class OAuth2LoginFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    /**
     * 카카오 KOE320 오류 설명의 인가 코드 ({@code "authorization code not found for code=..."}).
     * 요청에 code 가 두 번 붙으면 쉼표로 이어서 돌려주므로 공백·따옴표 전까지 통째로 가린다.
     */
    private static final Pattern AUTHORIZATION_CODE = Pattern.compile("code=[^\\s\"]+");

    private final String redirectUri;

    public OAuth2LoginFailureHandler(
            @Value("${app.auth.failure-redirect:http://localhost:3000/login}") String redirectUri
    ) {
        this.redirectUri = redirectUri;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException {
        // 상세 원인은 서버 로그에만 남긴다. 카카오가 돌려준 인가 코드는 가린다 (#193).
        log.warn("카카오 로그인 실패: {}", maskAuthorizationCode(exception.getMessage()));

        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        String target = UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam("error", "login_failed")
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUriString();

        getRedirectStrategy().sendRedirect(request, response, target);
    }

    private static String maskAuthorizationCode(String message) {
        if (message == null) {
            return null;
        }
        return AUTHORIZATION_CODE.matcher(message).replaceAll("code=***");
    }
}
