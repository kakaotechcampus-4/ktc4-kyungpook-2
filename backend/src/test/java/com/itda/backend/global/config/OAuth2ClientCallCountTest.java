package com.itda.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationProvider;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken;
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

import com.itda.backend.global.security.oauth.OAuth2LoginFailureHandler;
import com.sun.net.httpserver.HttpServer;

@ExtendWith(OutputCaptureExtension.class)
class OAuth2ClientCallCountTest {

    private static final String CLIENT_SECRET = "test-client-secret";
    private static final String AUTHORIZATION_CODE = "one-time-code";
    private static final String ACCESS_TOKEN = "kakao-access-token";

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private ClientRegistration kakao;
    private volatile int status = 503;
    private volatile String body = "{\"error\":\"server_error\"}";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Retry-After", "1");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        kakao = kakaoRegistration("http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @ParameterizedTest
    @ValueSource(ints = {503, 429})
    void 토큰_교환은_503_429_이어도_한_번만_보낸다(int status) {
        this.status = status;
        var client = new OAuth2ClientConfig().accessTokenResponseClient();

        assertThatThrownBy(() -> client.getTokenResponse(authorizationCodeGrant(AUTHORIZATION_CODE)))
                .isInstanceOf(OAuth2AuthorizationException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {503, 429})
    void 사용자_정보_조회는_503_429_이어도_한_번만_보낸다(int status) {
        this.status = status;
        var service = new OAuth2ClientConfig().oAuth2UserService(java.time.Clock.systemDefaultZone());

        assertThatThrownBy(() -> service.loadUser(userRequest(ACCESS_TOKEN)))
                .isInstanceOf(OAuth2AuthenticationException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @org.junit.jupiter.api.Test
    void 사용자_정보에_카카오에_묻기_직전_시각을_더한다() {
        this.status = 200;
        this.body = "{\"id\":123456789}";
        java.time.LocalDateTime fixed = java.time.LocalDateTime.of(2026, 11, 9, 10, 0);
        var clock = java.time.Clock.fixed(fixed.atZone(java.time.ZoneId.systemDefault()).toInstant(),
                java.time.ZoneId.systemDefault());

        var user = new OAuth2ClientConfig().oAuth2UserService(clock).loadUser(userRequest(ACCESS_TOKEN));

        assertThat(user.getAttributes().get("id")).isEqualTo(123456789);
        assertThat(user.getAttributes().get(OAuth2ClientConfig.KAKAO_VERIFIED_AT)).isEqualTo(fixed);
        assertThat(user.getName()).isEqualTo("123456789");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 503})
    void 토큰_교환_실패_메시지에_인가_코드와_client_secret_이_없다(int status) {
        this.status = status;
        this.body = "{\"error\":\"invalid_grant\",\"error_description\":\"bad request\"}";
        var client = new OAuth2ClientConfig().accessTokenResponseClient();

        assertThatThrownBy(() -> client.getTokenResponse(authorizationCodeGrant(AUTHORIZATION_CODE)))
                .isInstanceOf(OAuth2AuthorizationException.class)
                .satisfies(e -> assertThat(e.toString())
                        .doesNotContain(AUTHORIZATION_CODE)
                        .doesNotContain(CLIENT_SECRET));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 503})
    void 사용자_정보_조회_실패_메시지에_액세스_토큰이_없다(int status) {
        this.status = status;
        this.body = "{\"msg\":\"this access token does not exist\",\"code\":-401}";
        var service = new OAuth2ClientConfig().oAuth2UserService(java.time.Clock.systemDefaultZone());

        assertThatThrownBy(() -> service.loadUser(userRequest(ACCESS_TOKEN)))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(e -> assertThat(e.toString()).doesNotContain(ACCESS_TOKEN));
    }

    @Test
    void 카카오가_오류_설명에_돌려준_인가_코드는_실패_로그에서_가려진다(CapturedOutput output) throws Exception {
        this.status = 400;
        this.body = "{\"error\":\"invalid_grant\",\"error_description\":\"authorization code not found for code="
                + AUTHORIZATION_CODE + "\",\"error_code\":\"KOE320\"}";
        OAuth2ClientConfig config = new OAuth2ClientConfig();
        var provider = new OAuth2LoginAuthenticationProvider(config.accessTokenResponseClient(), config.oAuth2UserService(java.time.Clock.systemDefaultZone()));
        OAuth2AuthorizationExchange exchange = authorizationCodeGrant(AUTHORIZATION_CODE).getAuthorizationExchange();

        AuthenticationException failure = catchThrowableOfType(AuthenticationException.class,
                () -> provider.authenticate(new OAuth2LoginAuthenticationToken(kakao, exchange)));
        new OAuth2LoginFailureHandler("http://localhost:3000/login")
                .onAuthenticationFailure(new MockHttpServletRequest(), new MockHttpServletResponse(), failure);

        assertThat(output).doesNotContain(AUTHORIZATION_CODE);
        assertThat(output).contains("authorization code not found for code=***");
    }

    private static ClientRegistration kakaoRegistration(String baseUrl) {
        return ClientRegistration.withRegistrationId("kakao")
                .clientId("test-client-id")
                .clientSecret(CLIENT_SECRET)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/login/oauth2/code/kakao")
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
