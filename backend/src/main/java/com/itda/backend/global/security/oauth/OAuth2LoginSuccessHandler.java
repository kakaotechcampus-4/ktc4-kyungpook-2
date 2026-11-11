package com.itda.backend.global.security.oauth;

import com.itda.backend.global.config.OAuth2ClientConfig;
import com.itda.backend.domain.user.User;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.service.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;

@Slf4j
@Component
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private static final String KAKAO_ID = "id";

    private final JwtProvider jwtProvider;
    private final JwtCookie jwtCookie;
    private final OAuth2LoginFailureHandler failureHandler;
    private final UserService userService;
    private final String redirectUri;

    public OAuth2LoginSuccessHandler(
            JwtProvider jwtProvider,
            JwtCookie jwtCookie,
            OAuth2LoginFailureHandler failureHandler,
            UserService userService,
            @Value("${app.auth.success-redirect:http://localhost:3000/oauth/success}") String redirectUri
    ) {
        this.jwtProvider = jwtProvider;
        this.jwtCookie = jwtCookie;
        this.failureHandler = failureHandler;
        this.userService = userService;
        this.redirectUri = redirectUri;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        Object kakaoId = oAuth2User.getAttributes().get(KAKAO_ID);
        if (kakaoId == null) {
            log.error("카카오 사용자 정보에 {} 가 없습니다. user-name-attribute 설정을 확인하세요.", KAKAO_ID);
            failureHandler.onAuthenticationFailure(request, response,
                    new OAuth2AuthenticationException("kakao_id_missing"));
            return;
        }

        User user;
        try {
            LocalDateTime verifiedAt = oAuth2User.getAttributes().get(OAuth2ClientConfig.KAKAO_VERIFIED_AT)
                    instanceof LocalDateTime at ? at : null;
            user = userService.findOrCreateByKakaoId(String.valueOf(kakaoId), verifiedAt);
        } catch (RuntimeException e) {
            log.error("로그인 사용자 등록에 실패했습니다.", e);
            failureHandler.onAuthenticationFailure(request, response,
                    new OAuth2AuthenticationException("user_registration_failed"));
            return;
        }

        String token = jwtProvider.createToken(String.valueOf(user.getId()));
        jwtCookie.login(request, token).forEach(cookie ->
                response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString()));

        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        log.info("카카오 로그인 성공: userId={} role={}", user.getId(), user.getRole());
        getRedirectStrategy().sendRedirect(request, response, redirectUri);
    }
}
