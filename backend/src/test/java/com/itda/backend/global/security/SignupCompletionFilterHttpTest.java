package com.itda.backend.global.security;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.service.user.AccountAccess;
import com.itda.backend.service.user.UserService;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:signup-filter-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@ActiveProfiles("test")
@Import(SignupCompletionFilterHttpTest.ProtectedProbe.class)
class SignupCompletionFilterHttpTest {

    private static final String PROTECTED = "/api/v1/signup-filter-http-test/probe";
    private static final String PENDING_USER_ID = "1";
    private static final String UNAGREED_PARENT_ID = "2";

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
        when(userService.accessOf(PENDING_USER_ID)).thenReturn(AccountAccess.SIGNUP_NOT_COMPLETED);
        return getAs(PENDING_USER_ID, rawPath);
    }

    private HttpResponse<String> getAsUnagreedParent(String rawPath) throws Exception {
        when(userService.accessOf(UNAGREED_PARENT_ID)).thenReturn(AccountAccess.TERMS_NOT_AGREED);
        return getAs(UNAGREED_PARENT_ID, rawPath);
    }

    private HttpResponse<String> getAs(String userId, String rawPath) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath))
                .header("Cookie", JwtCookie.NAME + "=" + jwtProvider.createToken(userId))
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
    void percentEncodedPathCannotSkipTermsCheck() throws Exception {
        for (String rawPath : new String[]{
                PROTECTED,
                "/%61pi/v1/signup-filter-http-test/probe",
                "/api/v1/signup-filter-http-test/%70robe"}) {
            var response = getAsUnagreedParent(rawPath);

            assertThat(response.statusCode()).as(rawPath).isEqualTo(403);
            assertThat(response.body()).as(rawPath)
                    .contains("\"code\":\"TERMS_NOT_AGREED\"").doesNotContain("controller-reached");
        }
    }

    @Test
    void termsDocumentsPassForPendingAndUnagreedUsersEvenEncoded() throws Exception {
        for (String rawPath : new String[]{"/api/v1/terms?role=parent", "/api/v1/%74erms?role=parent"}) {
            assertThat(getAsPendingUser(rawPath).statusCode()).as(rawPath).isEqualTo(200);
            assertThat(getAsUnagreedParent(rawPath).statusCode()).as(rawPath).isEqualTo(200);
        }
    }

    @Test
    void pendingUserCanStillReachHealthCheck() throws Exception {
        var response = getAsPendingUser("/api/health");

        assertThat(response.statusCode()).isEqualTo(200);
    }
}
