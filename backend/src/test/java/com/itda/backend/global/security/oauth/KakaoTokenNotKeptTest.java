package com.itda.backend.global.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 카카오 로그인이 끝난 뒤 카카오 토큰을 서버에 남기지 않는다.
 *
 * <p>Spring Boot 기본 설정은 로그인할 때 받은 카카오 액세스 · 리프레시 토큰을 메모리 저장소에 회원번호별로 넣어 두고
 * 재시작할 때까지 지우지 않는다. 우리는 로그인 뒤 카카오 API 를 다시 부르지 않으므로 들고 있을 이유가 없다.
 * 카카오와의 토큰 교환 · 사용자 조회만 가짜로 바꾸고 나머지(state 검증 · 성공 핸들러)는 운영과 같은 흐름으로 돈다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KakaoTokenNotKeptTest {

    private static final String KAKAO_ID = "910001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OAuth2AuthorizedClientService authorizedClientService;

    @MockitoBean
    private OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> accessTokenResponseClient;

    @MockitoBean
    private OAuth2UserService<OAuth2UserRequest, OAuth2User> oAuth2UserService;

    @Test
    void 로그인이_끝나도_카카오_토큰이_서버에_남지_않는다() throws Exception {
        given(accessTokenResponseClient.getTokenResponse(any()))
                .willReturn(OAuth2AccessTokenResponse.withToken("kakao-access-token")
                        .tokenType(OAuth2AccessToken.TokenType.BEARER)
                        .expiresIn(3600)
                        .refreshToken("kakao-refresh-token")
                        .build());
        given(oAuth2UserService.loadUser(any()))
                .willReturn(new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")),
                        Map.of("id", Long.valueOf(KAKAO_ID)), "id"));

        MockHttpSession session = new MockHttpSession();
        String location = mockMvc.perform(get("/oauth2/authorization/kakao").session(session))
                .andReturn().getResponse().getHeader("Location");
        // 인가 URL 의 state 는 URL 인코딩돼 있다 — 카카오가 돌려줄 때처럼 원래 값으로 되돌려 보낸다
        String state = UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("state");
        state = URLDecoder.decode(state, StandardCharsets.UTF_8);

        mockMvc.perform(get("/login/oauth2/code/kakao").session(session)
                        .param("code", "one-time-code")
                        .param("state", state))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/oauth/success"));

        OAuth2AuthorizedClient kept = authorizedClientService.loadAuthorizedClient("kakao", KAKAO_ID);
        assertThat(kept).isNull();
    }
}
