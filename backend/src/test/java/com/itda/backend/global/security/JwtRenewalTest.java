package com.itda.backend.global.security;

import com.itda.backend.domain.user.RevokedSession;
import com.itda.backend.domain.user.User;
import com.itda.backend.fixture.TermsFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.user.RevokedSessionRepository;
import com.itda.backend.repository.user.TermsAgreementRepository;
import com.itda.backend.repository.user.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 쓰는 동안의 자동 연장과, 로그아웃한 로그인을 거절하는 동작. 출입증은 과거 시각 시계를 가진 {@link JwtProvider} 로
 * 같은 비밀키로 만들어 "몇 시간 전에 로그인한 사람" 을 흉내 낸다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jwt-renewal;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "app.auth.dev-login.enabled=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JwtRenewalTest {

    private static final String ME = "/api/v1/auth/me";
    private static final String LOGOUT = "/api/v1/auth/logout";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private TermsAgreementRepository termsAgreementRepository;
    @Autowired private RevokedSessionRepository revokedSessionRepository;

    @Value("${jwt.secret}") private String secret;
    @Value("${jwt.access-token-expiration-ms}") private long ttlMs;
    @Value("${jwt.max-session-ms:43200000}") private long maxSessionMs;

    private User parent;

    @BeforeEach
    void setUp() {
        parent = TermsFixture.agreeAll(termsAgreementRepository,
                userRepository.save(UserFixture.parent("kakao-renewal-parent", "김보호")));
    }

    @AfterEach
    void cleanUp() {
        revokedSessionRepository.deleteAll();
        termsAgreementRepository.deleteAll();
        userRepository.deleteAll();
    }

    /** {@code ago} 전에 로그인한 출입증. */
    private String loggedInAgo(Duration ago) {
        Clock past = Clock.offset(Clock.systemUTC(), ago.negated());
        return new JwtProvider(secret, ttlMs, maxSessionMs, past).createToken(String.valueOf(parent.getId()));
    }

    private static Cookie auth(String token) {
        return new Cookie(JwtCookie.NAME, token);
    }

    private static List<String> accessTokenCookies(MockHttpServletResponse response) {
        return response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(cookie -> cookie.startsWith(JwtCookie.NAME + "="))
                .toList();
    }

    private static String valueOf(String setCookie) {
        return setCookie.substring(JwtCookie.NAME.length() + 1, setCookie.indexOf(';'));
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    /** CSRF 토큰을 실제로 받아 되돌려 보내는 로그아웃. */
    private MockHttpServletResponse logout(MockHttpServletRequestBuilder request, String token) throws Exception {
        Cookie csrf = perform(get("/api/health")).getCookie("XSRF-TOKEN");
        return perform(request.cookie(auth(token), csrf).header("X-XSRF-TOKEN", csrf.getValue()));
    }

    @Test
    void freshTokenIsNotRenewed() throws Exception {
        MockHttpServletResponse response = perform(get(ME).cookie(auth(loggedInAgo(Duration.ZERO))));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(accessTokenCookies(response)).isEmpty();
    }

    @Test
    void tokenWithLessThanHalfLeftIsRenewedAndTheNewCookieWorks() throws Exception {
        MockHttpServletResponse response = perform(get(ME).cookie(auth(loggedInAgo(Duration.ofHours(2)))));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(accessTokenCookies(response)).singleElement().satisfies(cookie -> assertThat(cookie)
                .contains("Max-Age=10800").contains("HttpOnly").contains("SameSite=Lax").contains("Path=/"));

        String renewed = valueOf(accessTokenCookies(response).get(0));
        assertThat(perform(get(ME).cookie(auth(renewed))).getStatus()).isEqualTo(200);
    }

    /** 로그아웃 응답에 연장 쿠키가 같이 실리면 만료 쿠키를 덮을 수 있다. */
    @Test
    void logoutDoesNotRenew() throws Exception {
        MockHttpServletResponse response = logout(post(LOGOUT), loggedInAgo(Duration.ofHours(2)));

        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(accessTokenCookies(response)).singleElement()
                .satisfies(cookie -> assertThat(cookie).contains("Max-Age=0"));
    }

    /** 인코딩한 주소로 보내도 컨트롤러와 같은 기준으로 로그아웃으로 읽는다. */
    @Test
    void encodedLogoutPathDoesNotRenew() throws Exception {
        MockHttpServletResponse response =
                logout(post(URI.create("/api/v1/auth/%6Cogout")), loggedInAgo(Duration.ofHours(2)));

        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(accessTokenCookies(response)).singleElement()
                .satisfies(cookie -> assertThat(cookie).contains("Max-Age=0"));
    }

    /** 개발용 로그인은 새 로그인 쿠키를 싣는다. 기존 쿠키의 연장 쿠키가 같이 실리면 어느 쪽이 남을지 순서에 달린다. */
    @Test
    void devLoginWithAnOldCookieIssuesOnlyTheNewLoginCookie() throws Exception {
        String old = loggedInAgo(Duration.ofHours(2));

        MockHttpServletResponse response = perform(get("/api/v1/dev/login").param("as", "renewal").cookie(auth(old)));

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(accessTokenCookies(response)).singleElement()
                .satisfies(cookie -> assertThat(valueOf(cookie)).isNotEqualTo(old));
    }

    @Test
    void expiredTokenIsUnauthorizedAndNotRenewed() throws Exception {
        MockHttpServletResponse response = perform(get(ME).cookie(auth(loggedInAgo(Duration.ofHours(4)))));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(accessTokenCookies(response)).isEmpty();
    }

    /**
     * 로그아웃 직전에 나간 요청이 연장 쿠키 Y 를 받아 두었다가 로그아웃보다 늦게 도착해 Y 를 심는 경우. Y 는 같은 로그인
     * 번호라 쓸 수 없다. 로그아웃한 X 도 그대로 쓸 수 없다.
     */
    @Test
    void renewedCookieArrivingAfterLogoutCannotBeUsed() throws Exception {
        String x = loggedInAgo(Duration.ofHours(2));
        String y = valueOf(accessTokenCookies(perform(get(ME).cookie(auth(x)))).get(0));

        assertThat(logout(post(LOGOUT), x).getStatus()).isEqualTo(204);

        MockHttpServletResponse withY = perform(get(ME).cookie(auth(y)));
        assertThat(withY.getStatus()).isEqualTo(401);
        // 늦게 도착할 수 있는 응답이라 만료 쿠키도 내려주지 않는다 — 그 사이 새로 로그인한 쿠키를 지우지 않게
        assertThat(accessTokenCookies(withY)).isEmpty();
        assertThat(perform(get(ME).cookie(auth(x))).getStatus()).isEqualTo(401);
    }

    /** 1기관 1계정이라 한 사람의 로그아웃이 같은 계정의 다른 로그인을 끊으면 안 된다. */
    @Test
    void otherLoginOfTheSameUserSurvivesLogout() throws Exception {
        String mine = loggedInAgo(Duration.ofMinutes(10));
        String colleague = loggedInAgo(Duration.ofMinutes(5));

        assertThat(logout(post(LOGOUT), mine).getStatus()).isEqualTo(204);

        assertThat(perform(get(ME).cookie(auth(colleague))).getStatus()).isEqualTo(200);
    }

    @Test
    void loggingOutTwiceKeepsOneRecord() throws Exception {
        String token = loggedInAgo(Duration.ofMinutes(10));

        assertThat(logout(post(LOGOUT), token).getStatus()).isEqualTo(204);
        assertThat(logout(post(LOGOUT), token).getStatus()).isEqualTo(204);

        assertThat(revokedSessionRepository.findAll()).singleElement()
                .extracting(RevokedSession::getUserId).isEqualTo(parent.getId());
    }

    /** 자동 연장 전에 발급된 출입증은 로그인 번호가 없어 남길 것이 없다. 쿠키만 지운다. */
    @Test
    void loggingOutWithALegacyTokenOnlyExpiresTheCookie() throws Exception {
        String legacy = Jwts.builder()
                .subject(String.valueOf(parent.getId()))
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(Duration.ofHours(1))))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .compact();

        MockHttpServletResponse response = logout(post(LOGOUT), legacy);

        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(accessTokenCookies(response)).singleElement()
                .satisfies(cookie -> assertThat(cookie).contains("Max-Age=0"));
        assertThat(revokedSessionRepository.count()).isZero();
    }

    /** 옛 출입증은 통과하지만 연장하지 않는다 — 로그아웃으로 거절할 번호가 없다. */
    @Test
    void legacyTokenIsAcceptedButNotRenewed() throws Exception {
        String legacy = Jwts.builder()
                .subject(String.valueOf(parent.getId()))
                .issuedAt(Date.from(Instant.now().minus(Duration.ofHours(2))))
                .expiration(Date.from(Instant.now().plus(Duration.ofHours(1))))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .compact();

        MockHttpServletResponse response = perform(get(ME).cookie(auth(legacy)));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(accessTokenCookies(response)).isEmpty();
    }
}
