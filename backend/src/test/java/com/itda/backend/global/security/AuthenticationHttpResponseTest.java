package com.itda.backend.global.security;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import com.itda.backend.exception.UserErrorCode;
import com.itda.backend.exception.UserException;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;

/** MockMvc가 생략하는 서블릿 ERROR 디스패치와 실제 쿠키 응답까지 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:auth-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@ActiveProfiles("test")
@Import(AuthenticationHttpResponseTest.ProtectedProbe.class)
class AuthenticationHttpResponseTest {

    private static final String PROTECTED = "/api/v1/filter-http-test/probe";

    @LocalServerPort private int port;
    @Autowired private JwtProvider jwtProvider;
    @MockitoBean private UserService userService;

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @RestController
    static class ProtectedProbe {
        @GetMapping(PROTECTED)
        Map<String, String> probe() {
            return Map.of("status", "controller-reached");
        }
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) {
            request.header("Cookie", JwtCookie.NAME + "=" + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void databaseOutageReturns500AndTheSameTokenWorksAfterRecovery() throws Exception {
        String token = jwtProvider.createToken("1");
        when(userService.isSignupCompleted("1"))
                .thenThrow(new DataAccessResourceFailureException("private-jdbc-host-and-sql"));

        var failure = get(PROTECTED, token);
        assertThat(failure.statusCode()).isEqualTo(500);
        assertThat(failure.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        assertThat(failure.body()).contains("\"result\":\"FAIL\"", "\"code\":\"INTERNAL_SERVER_ERROR\"")
                .doesNotContain("private-jdbc-host-and-sql", "controller-reached");
        assertThat(failure.headers().allValues("Set-Cookie"))
                .noneMatch(cookie -> cookie.startsWith(JwtCookie.NAME + "=") || cookie.startsWith("JSESSIONID="));

        doReturn(true).when(userService).isSignupCompleted("1");
        var recovered = get(PROTECTED, token);
        assertThat(recovered.statusCode()).isEqualTo(200);
        assertThat(recovered.body()).contains("controller-reached");
    }

    @Test
    void oauthAuthorizationStillCreatesAStateSession() throws Exception {
        var response = get("/oauth2/authorization/kakao", null);
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location").orElseThrow()).contains("state=");
        assertThat(response.headers().allValues("Set-Cookie"))
                .anyMatch(cookie -> cookie.startsWith("JSESSIONID="));
    }

    @Test
    void missingMemberIs401AndPendingMemberIs403() throws Exception {
        when(userService.isSignupCompleted("2"))
                .thenThrow(new UserException(UserErrorCode.SESSION_USER_NOT_FOUND));
        var missing = get(PROTECTED, jwtProvider.createToken("2"));
        assertThat(missing.statusCode()).isEqualTo(401);
        assertThat(missing.body()).contains("\"code\":\"SESSION_USER_NOT_FOUND\"");

        when(userService.isSignupCompleted("3")).thenReturn(false);
        var pending = get(PROTECTED, jwtProvider.createToken("3"));
        assertThat(pending.statusCode()).isEqualTo(403);
        assertThat(pending.body()).contains("\"code\":\"SIGNUP_NOT_COMPLETED\"");
    }
}
