package com.itda.backend.global.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;

import com.itda.backend.domain.User;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.UserRepository;

/**
 * 로그인 성공 이후 우리가 맡은 부분 — 회원 등록과 출입증 발급.
 * 카카오를 실제로 다녀오지 않고 핸들러를 직접 구동한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class OAuth2LoginSuccessHandlerTest {

    @Autowired
    private OAuth2LoginSuccessHandler successHandler;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtProvider jwtProvider;

    private static UsernamePasswordAuthenticationToken kakaoLogin(Object kakaoId, String nickname) {
        Map<String, Object> attributes = nickname == null
                ? Map.of("id", kakaoId)
                : Map.of("id", kakaoId, "properties", Map.of("nickname", nickname));
        OAuth2User principal = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")), attributes, "id");
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private MockHttpServletResponse login(MockHttpServletRequest request, Object kakaoId, String nickname)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        successHandler.onAuthenticationSuccess(request, response, kakaoLogin(kakaoId, nickname));
        return response;
    }

    /** 역할은 가입 API 에서 정한다. 첫 로그인은 역할·소속 없는 가입 미완료 회원을 만든다. */
    @Test
    void firstLoginStoresUserWithoutRole() throws Exception {
        MockHttpServletResponse response = login(new MockHttpServletRequest(), 900001L, "박지현");

        User saved = userRepository.findByKakaoId("900001").orElseThrow();
        assertThat(saved.getRole()).isNull();
        assertThat(saved.getOrganizationId()).isNull();
        assertThat(saved.getName()).isEqualTo("박지현");
        assertThat(response.getStatus()).isEqualTo(302);
    }

    /** 출입증의 subject 가 카카오 회원번호가 아니라 내부 userId 여야 한다. */
    @Test
    void issuedTokenCarriesInternalUserIdAsSubject() throws Exception {
        MockHttpServletResponse response = login(new MockHttpServletRequest(), 900002L, "김하늘");

        User saved = userRepository.findByKakaoId("900002").orElseThrow();
        String token = response.getCookie(JwtCookie.NAME).getValue();

        assertThat(jwtProvider.getSubject(token)).isEqualTo(String.valueOf(saved.getId()));
        assertThat(jwtProvider.getSubject(token)).isNotEqualTo("900002");
    }

    @Test
    void loggingInAgainReusesTheSameUserRow() throws Exception {
        login(new MockHttpServletRequest(), 900003L, "이서준");
        Long firstId = userRepository.findByKakaoId("900003").orElseThrow().getId();

        login(new MockHttpServletRequest(), 900003L, "이서준");

        assertThat(userRepository.findByKakaoId("900003").orElseThrow().getId()).isEqualTo(firstId);
        assertThat(userRepository.findAll().stream()
                .filter(u -> u.getKakaoId().equals("900003")).count()).isEqualTo(1);
    }

    /** 닉네임 동의를 거부하면 properties 가 아예 오지 않는다. 로그인은 그대로 되어야 한다. */
    @Test
    void nicknameConsentRefusedStillLogsIn() throws Exception {
        login(new MockHttpServletRequest(), 900004L, null);

        User saved = userRepository.findByKakaoId("900004").orElseThrow();
        assertThat(saved.getName()).isNull();
    }

    /** 나중에 동의를 철회해 닉네임이 빠져도, 이미 저장한 이름을 지우지 않는다. */
    @Test
    void laterLoginWithoutNicknameKeepsTheStoredName() throws Exception {
        login(new MockHttpServletRequest(), 900005L, "최유나");

        login(new MockHttpServletRequest(), 900005L, null);

        assertThat(userRepository.findByKakaoId("900005").orElseThrow().getName()).isEqualTo("최유나");
    }
}
