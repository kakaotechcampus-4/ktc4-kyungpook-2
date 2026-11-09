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

import com.itda.backend.domain.user.User;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.user.UserRepository;

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

    @Test
    void firstLoginStoresUserWithoutRole() throws Exception {
        MockHttpServletResponse response = login(new MockHttpServletRequest(), 900001L, "박지현");

        User saved = userRepository.findByKakaoId("900001").orElseThrow();
        assertThat(saved.getRole()).isNull();
        assertThat(saved.getOrganizationId()).isNull();
        assertThat(response.getStatus()).isEqualTo(302);
    }

    @Test
    void issuedTokenCarriesInternalUserIdAsSubject() throws Exception {
        MockHttpServletResponse response = login(new MockHttpServletRequest(), 900002L, "김하늘");

        User saved = userRepository.findByKakaoId("900002").orElseThrow();
        String token = response.getCookie(JwtCookie.NAME).getValue();

        assertThat(jwtProvider.parse(token).orElseThrow().subject()).isEqualTo(String.valueOf(saved.getId()));
        assertThat(jwtProvider.parse(token).orElseThrow().subject()).isNotEqualTo("900002");
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

    @Test
    void loginWorksWithOnlyTheKakaoId() throws Exception {
        MockHttpServletResponse response = login(new MockHttpServletRequest(), 900004L, null);

        assertThat(userRepository.findByKakaoId("900004")).isPresent();
        assertThat(response.getStatus()).isEqualTo(302);
    }
}
