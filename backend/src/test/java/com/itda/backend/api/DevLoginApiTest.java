package com.itda.backend.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.repository.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 개발용 로그인을 켠 상태에서 실제 필터 체인(가입 미완료 차단 포함)을 거쳐 검증한다.
 * 리다이렉트를 따라가지 않아야 302 와 출입증 쿠키를 그대로 볼 수 있다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:dev-login-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "app.auth.dev-login.enabled=true",
                "app.auth.success-redirect=" + DevLoginApiTest.SUCCESS_REDIRECT
        })
@ActiveProfiles("test")
class DevLoginApiTest {

    static final String SUCCESS_REDIRECT = "http://front.test/oauth/success";

    @LocalServerPort private int port;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @AfterEach
    void cleanUp() {
        userRepository.deleteAll();
    }

    private HttpResponse<String> get(String path, String accessToken) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (accessToken != null) {
            builder.header("Cookie", JwtCookie.NAME + "=" + accessToken);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> devLogin(String account, String accessToken) throws Exception {
        return get("/api/v1/dev/login?as=" + account, accessToken);
    }

    private static String accessTokenOf(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(cookie -> cookie.startsWith(JwtCookie.NAME + "="))
                .map(cookie -> cookie.substring(JwtCookie.NAME.length() + 1, cookie.indexOf(';')))
                .findFirst()
                .orElseThrow(() -> new AssertionError("access_token 쿠키가 없습니다: " + response.headers()));
    }

    private JsonNode me(String accessToken) throws Exception {
        var response = get("/api/v1/auth/me", accessToken);
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body()).get("data");
    }

    @Test
    void firstLoginCreatesPendingUserAndRedirectsWithAccessToken() throws Exception {
        var response = devLogin("parent1", null);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValue(SUCCESS_REDIRECT);
        assertThat(response.body()).isEmpty();
        assertThat(userRepository.findByKakaoId("dev:parent1")).hasValueSatisfying(user -> {
            assertThat(user.isSignupCompleted()).isFalse();
            assertThat(user.getName()).isEqualTo("parent1");
        });

        JsonNode me = me(accessTokenOf(response));
        assertThat(me.get("signupCompleted").asBoolean()).isFalse();
        assertThat(me.get("name").asText()).isEqualTo("parent1");
    }

    @Test
    void sameNameLogsInAsSameUser() throws Exception {
        String first = me(accessTokenOf(devLogin("parent1", null))).get("userId").asText();
        String second = me(accessTokenOf(devLogin("parent1", null))).get("userId").asText();

        assertThat(second).isEqualTo(first);
        assertThat(userRepository.count()).isEqualTo(1);
    }

    /** 가입 미완료 회원의 출입증을 든 채로도 다른 테스트 회원으로 바꿔 들어갈 수 있어야 한다. */
    @Test
    void pendingUserCanSwitchToAnotherName() throws Exception {
        String parentToken = accessTokenOf(devLogin("parent1", null));
        String parentId = me(parentToken).get("userId").asText();

        var switched = devLogin("org1", parentToken);

        assertThat(switched.statusCode()).isEqualTo(302);
        assertThat(me(accessTokenOf(switched)).get("userId").asText()).isNotEqualTo(parentId);
        assertThat(userRepository.count()).isEqualTo(2);
    }

    @Test
    void invalidOrMissingNameIsRejectedWithoutCreatingUser() throws Exception {
        for (String query : new String[]{"?as=Parent!", "?as=" + "a".repeat(21), "?as=", ""}) {
            var response = get("/api/v1/dev/login" + query, null);

            assertThat(response.statusCode()).as(query).isEqualTo(400);
            assertThat(response.body()).as(query).contains("\"code\":\"INVALID_REQUEST\"");
            assertThat(response.headers().allValues("Set-Cookie")).as(query)
                    .noneMatch(cookie -> cookie.startsWith(JwtCookie.NAME + "="));
        }
        assertThat(userRepository.count()).isZero();
    }

    @Test
    void openApiDocumentsDevLoginWhenEnabled() throws Exception {
        var response = get("/v3/api-docs", null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode responses = objectMapper.readTree(response.body())
                .path("paths").path("/api/v1/dev/login").path("get").path("responses");
        assertThat(responses.path("302").path("headers").has("Location")).isTrue();
        assertThat(responses.path("302").path("headers").has("Set-Cookie")).isTrue();
        assertThat(responses.has("400")).isTrue();
    }
}
