package com.itda.backend.global.security;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 가입 미완료 차단이 실제 Tomcat 이 넘겨주는 경로 기준으로도 빠지지 않는지 검증한다.
 *
 * <p>MockMvc 는 요청 URI 를 서블릿 컨테이너처럼 다루지 않아, 인코딩된 경로로 필터를 건너뛰는 문제를 잡지 못한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:signup-filter-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@ActiveProfiles("test")
@Import(SignupCompletionFilterHttpTest.ProtectedProbe.class)
class SignupCompletionFilterHttpTest {

    private static final String PROTECTED = "/api/v1/signup-filter-http-test/probe";
    private static final String PENDING_USER_ID = "1";

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

    private HttpResponse<String> getAsPendingUser(String rawPath) throws Exception {
        when(userService.isSignupCompleted(PENDING_USER_ID)).thenReturn(false);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath))
                .header("Cookie", JwtCookie.NAME + "=" + jwtProvider.createToken(PENDING_USER_ID))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void pendingUserIsForbiddenFromProtectedApi() throws Exception {
        var response = getAsPendingUser(PROTECTED);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":\"SIGNUP_NOT_COMPLETED\"").doesNotContain("controller-reached");
    }

    /** "/%61pi" 는 Tomcat 이 "/api" 로 풀어서 컨트롤러까지 간다. 필터도 같은 경로로 판단해야 한다. */
    @Test
    void percentEncodedPathCannotSkipSignupCheck() throws Exception {
        for (String rawPath : new String[]{
                "/%61pi/v1/signup-filter-http-test/probe",
                "/%61%70%69/v1/signup-filter-http-test/probe",
                "/api/v1/signup-filter-http-test/%70robe"}) {
            var response = getAsPendingUser(rawPath);

            assertThat(response.statusCode()).as(rawPath).isEqualTo(403);
            assertThat(response.body()).as(rawPath)
                    .contains("\"code\":\"SIGNUP_NOT_COMPLETED\"").doesNotContain("controller-reached");
        }
    }

    /**
     * 경로를 꾸미는 다른 방법도 컨트롤러에 닿지 않는다. 대부분 Spring Security 방화벽이 먼저 거부하고,
     * 거부된 요청의 ERROR 디스패치가 인증 실패로 끝나 401 이 되기도 한다.
     */
    @Test
    void otherPathTricksNeverReachController() throws Exception {
        for (String rawPath : new String[]{
                "//api/v1/signup-filter-http-test/probe",
                "/api;x=1/v1/signup-filter-http-test/probe",
                "/x%2F..%2Fapi/v1/signup-filter-http-test/probe",
                "/x/%2e%2e/api/v1/signup-filter-http-test/probe"}) {
            var response = getAsPendingUser(rawPath);

            assertThat(response.statusCode()).as(rawPath).isBetween(400, 499);
            assertThat(response.body()).as(rawPath).doesNotContain("controller-reached");
        }
    }

    @Test
    void pendingUserCanStillReachHealthCheck() throws Exception {
        var response = getAsPendingUser("/api/health");

        assertThat(response.statusCode()).isEqualTo(200);
    }
}
