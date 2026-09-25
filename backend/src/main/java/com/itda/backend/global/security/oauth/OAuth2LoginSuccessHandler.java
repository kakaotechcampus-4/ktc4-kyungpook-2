package com.itda.backend.global.security.oauth;

import com.itda.backend.domain.User;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.service.UserService;
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
import java.util.Map;

/**
 * Spring Security 가 카카오 인증(state 검증 · 토큰 교환 · 사용자 조회)을 끝낸 뒤 불린다.
 * 여기서부터가 우리 몫이다 — 회원을 등록하고, 자체 출입증을 만들어 브라우저에 심고,
 * 프론트로 돌려보낸다. 처음 온 사람은 역할 없는(가입 미완료) 회원으로 등록되고,
 * 프론트가 /auth/me 로 그 상태를 보고 가입 화면을 띄운다.
 */
@Slf4j
@Component
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    /** 카카오 사용자 정보 응답의 최상위 식별자. application.yml 의 user-name-attribute 와 같다. */
    private static final String KAKAO_ID = "id";

    /** 닉네임은 properties.nickname 에 들어온다 (scope: profile_nickname). */
    private static final String KAKAO_PROPERTIES = "properties";
    private static final String KAKAO_NICKNAME = "nickname";

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
            /*
             * user-name-attribute 설정과 카카오 응답이 어긋난 경우. 토큰을 만들면 안 된다.
             *
             * 여기서 그냥 예외를 던지면 안 된다 — 성공 핸들러는 필터 안이라
             * GlobalExceptionHandler 가 잡지 못하고, 사용자는 톰캣 기본 500 페이지를 본다.
             * 다른 실패 경로와 똑같이 프론트 로그인 화면으로 돌려보낸다.
             */
            log.error("카카오 사용자 정보에 {} 가 없습니다. user-name-attribute 설정을 확인하세요.", KAKAO_ID);
            failureHandler.onAuthenticationFailure(request, response,
                    new OAuth2AuthenticationException("kakao_id_missing"));
            return;
        }

        User user;
        try {
            user = userService.findOrCreateByKakaoId(
                    String.valueOf(kakaoId), extractNickname(oAuth2User));
        } catch (RuntimeException e) {
            // DB 장애나 동시 첫 로그인 충돌. 여기서도 예외를 던지면 톰캣 기본 오류 화면이 뜬다.
            log.error("로그인 사용자 등록에 실패했습니다.", e);
            failureHandler.onAuthenticationFailure(request, response,
                    new OAuth2AuthenticationException("user_registration_failed"));
            return;
        }

        // 출입증의 주인은 카카오 회원번호가 아니라 내부 userId 다.
        String token = jwtProvider.createToken(String.valueOf(user.getId()));
        response.addHeader(HttpHeaders.SET_COOKIE, jwtCookie.issue(token).toString());

        // 세션은 인가 요청(state)을 잠시 보관하려고 만들어진 것뿐이다. 역할이 끝났으니 버린다.
        // 남겨두면 JWT 와 세션이라는 인증 수단이 둘이 되어 수명이 어긋난다.
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        // 카카오 회원번호는 남기지 않는다.
        log.info("카카오 로그인 성공: userId={} role={}", user.getId(), user.getRole());
        getRedirectStrategy().sendRedirect(request, response, redirectUri);
    }

    /**
     * 닉네임은 사용자가 동의를 거부할 수 있다. 그러면 properties 자체가 없거나 비어 있으므로
     * 방어적으로 읽고 null 을 돌려준다.
     */
    private String extractNickname(OAuth2User oAuth2User) {
        if (!(oAuth2User.getAttributes().get(KAKAO_PROPERTIES) instanceof Map<?, ?> properties)) {
            return null;
        }
        Object nickname = properties.get(KAKAO_NICKNAME);
        return nickname == null ? null : String.valueOf(nickname);
    }
}
