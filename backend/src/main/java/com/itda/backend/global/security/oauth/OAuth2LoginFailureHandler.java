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

@Slf4j
@Component
public class OAuth2LoginFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private static final Pattern AUTHORIZATION_CODE = Pattern.compile("(?<![\\w-])code=[^\\s\"]+");

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
