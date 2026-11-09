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
