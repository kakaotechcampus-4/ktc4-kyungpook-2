package com.itda.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;

import com.sun.net.httpserver.HttpServer;

/**
 * 카카오가 실제로 몇 번 불리는지 센다 (#193).
 *
 * <p>Apache HttpClient 5 는 기본으로 503·429 를 한 번 더 보낸다. 토큰 교환 요청에는 한 번만 쓸 수 있는 인가 코드가
 * 들어 있어서 두 번째 요청은 {@code invalid_grant} 가 된다. 그래서 진짜 HTTP 서버를 띄우고 운영과 같은 설정 클래스로 부른다
 * ({@code com.itda.backend.service.agent.AiAgentCallCountTest} 와 같은 방식).
 */
class OAuth2ClientCallCountTest {

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private ClientRegistration kakao;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        // 카카오가 잠깐 바쁠 때의 응답
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            byte[] body = "{\"error\":\"server_error\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        kakao = kakaoRegistration("http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 토큰_교환은_503_이어도_한_번만_보낸다() {
        var client = new OAuth2ClientConfig().accessTokenResponseClient();

        assertThatThrownBy(() -> client.getTokenResponse(authorizationCodeGrant("one-time-code")))
                .isInstanceOf(OAuth2AuthorizationException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void 사용자_정보_조회는_503_이어도_한_번만_보낸다() {
        var service = new OAuth2ClientConfig().oAuth2UserService();

        assertThatThrownBy(() -> service.loadUser(userRequest("kakao-access-token")))
                .isInstanceOf(OAuth2AuthenticationException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    /** application.yml 의 kakao 등록과 같은 값. 주소만 로컬 서버로 바꾼다. */
    private static ClientRegistration kakaoRegistration(String baseUrl) {
        return ClientRegistration.withRegistrationId("kakao")
                .clientId("test-client-id")
                .clientSecret("test-client-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/login/oauth2/code/kakao")
                .scope("profile_nickname")
                .authorizationUri(baseUrl + "/oauth/authorize")
                .tokenUri(baseUrl + "/oauth/token")
                .userInfoUri(baseUrl + "/v2/user/me")
                .userNameAttributeName("id")
                .build();
    }

    private OAuth2AuthorizationCodeGrantRequest authorizationCodeGrant(String code) {
        OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(kakao.getProviderDetails().getAuthorizationUri())
                .clientId(kakao.getClientId())
                .redirectUri(kakao.getRedirectUri())
                .state("state")
                .build();
        OAuth2AuthorizationResponse response = OAuth2AuthorizationResponse.success(code)
                .redirectUri(kakao.getRedirectUri())
                .state("state")
                .build();
        return new OAuth2AuthorizationCodeGrantRequest(kakao, new OAuth2AuthorizationExchange(request, response));
    }

    private OAuth2UserRequest userRequest(String accessToken) {
        Instant now = Instant.now();
        return new OAuth2UserRequest(kakao,
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, accessToken, now, now.plusSeconds(60)));
    }
}
