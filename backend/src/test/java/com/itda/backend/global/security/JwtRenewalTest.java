package com.itda.backend.global.security;

import com.itda.backend.domain.user.RevokedSession;
import com.itda.backend.domain.user.User;
import com.itda.backend.fixture.MutableClock;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.net.HttpCookie;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jwt-renewal;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "app.auth.dev-login.enabled=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(JwtRenewalTest.TestClockConfig.class)
class JwtRenewalTest {

    private static final String ME = "/api/v1/auth/me";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 9, 0);

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private MutableClock clock;
    @Autowired private UserRepository userRepository;
    @Autowired private TermsAgreementRepository termsAgreementRepository;
    @Autowired private RevokedSessionRepository revokedSessionRepository;

    @Value("${jwt.secret}") private String secret;
    @Value("${jwt.access-token-expiration-ms}") private long ttlMs;
    @Value("${jwt.max-session-ms:43200000}") private long maxSessionMs;

    private User parent;

    @BeforeEach
    void setUp() {
        clock.setTo(NOW);
        parent = TermsFixture.agreeAll(termsAgreementRepository,
                userRepository.save(UserFixture.parent("kakao-renewal-parent")));
    }

    @AfterEach
    void cleanUp() {
        revokedSessionRepository.deleteAll();
        termsAgreementRepository.deleteAll();
        userRepository.deleteAll();
    }

    private String loggedInAgo(Duration ago) {
        Clock past = Clock.offset(clock, ago.negated());
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
        return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
    }

    private static List<String> renewalCookies(MockHttpServletResponse response) {
        return response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(cookie -> cookie.startsWith(JwtCookie.RENEWAL_PREFIX))
                .toList();
    }

    private static Cookie renewalCookie(MockHttpServletResponse response) {
        assertThat(renewalCookies(response)).hasSize(1);
        HttpCookie parsed = HttpCookie.parse(renewalCookies(response).get(0)).get(0);
        return new Cookie(parsed.getName(), parsed.getValue());
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private MockHttpServletResponse logout(MockHttpServletRequestBuilder request, String token) throws Exception {
        Cookie csrf = perform(get("/api/health")).getCookie("XSRF-TOKEN");
        return perform(request.cookie(auth(token), csrf).header("X-XSRF-TOKEN", csrf.getValue()));
    }

    private record StoredCookie(Cookie cookie, Instant expiresAt) {}

    private class BrowserCookies {
        private final Map<String, StoredCookie> cookies = new LinkedHashMap<>();

        void accept(MockHttpServletResponse response) {
            for (String header : response.getHeaders(HttpHeaders.SET_COOKIE)) {
                for (HttpCookie cookie : HttpCookie.parse(header)) {
                    if (cookie.getMaxAge() == 0) {
                        cookies.remove(cookie.getName());
                    } else {
                        Instant expiresAt = cookie.getMaxAge() < 0 ? null
                                : clock.instant().plusSeconds(cookie.getMaxAge());
                        cookies.put(cookie.getName(), new StoredCookie(
                                new Cookie(cookie.getName(), cookie.getValue()), expiresAt));
                    }
                }
            }
        }

        Cookie[] all() {
            cookies.values().removeIf(stored -> stored.expiresAt() != null
                    && !clock.instant().isBefore(stored.expiresAt()));
            return cookies.values().stream().map(StoredCookie::cookie).toArray(Cookie[]::new);
        }

        String value(String name) {
            return cookies.get(name).cookie().getValue();
        }
    }

    private void login(BrowserCookies browser, String account) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/dev/login").param("as", account);
        if (browser.all().length > 0) {
            request.cookie(browser.all());
        }
        MockHttpServletResponse response = perform(request);
        assertThat(response.getStatus()).isEqualTo(302);
        browser.accept(response);
    }

    @Test
    void lateRenewalDoesNotReplaceNewLoginAfterLogout() throws Exception {
        BrowserCookies browser = new BrowserCookies();
        login(browser, "renewal-race");
        clock.advance(Duration.ofHours(2));
        MockHttpServletResponse delayed = perform(get(ME).cookie(browser.all()));
        assertThat(delayed.getStatus()).isEqualTo(200);
        assertThat(renewalCookies(delayed)).hasSize(1);

        browser.accept(perform(get("/api/health").cookie(browser.all())));
        MockHttpServletResponse logout = perform(post(LOGOUT).cookie(browser.all())
                .header("X-XSRF-TOKEN", browser.value("XSRF-TOKEN")));
        assertThat(logout.getStatus()).isEqualTo(204);
        browser.accept(logout);
        login(browser, "renewal-race");
        String newLogin = browser.value(JwtCookie.NAME);

        browser.accept(delayed);

        assertThat(perform(get(ME).cookie(browser.all())).getStatus()).isEqualTo(200);
        assertThat(browser.value(JwtCookie.NAME)).isEqualTo(newLogin);
    }

    @Test
    void lateRenewalDoesNotRestorePreviousAccountAfterDevLogin() throws Exception {
        BrowserCookies browser = new BrowserCookies();
        login(browser, "renewal-old");
        clock.advance(Duration.ofHours(2));
        MockHttpServletResponse delayed = perform(get(ME).cookie(browser.all()));
        assertThat(delayed.getStatus()).isEqualTo(200);
        assertThat(renewalCookies(delayed)).hasSize(1);
        login(browser, "renewal-new");
        String currentUser = perform(get(ME).cookie(browser.all())).getContentAsString();

        browser.accept(delayed);

        MockHttpServletResponse response = perform(get(ME).cookie(browser.all()));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo(currentUser);
    }

    @Test
    void latePreviousLoginResponseCannotInterruptCurrentRenewedLogin() throws Exception {
        BrowserCookies browser = new BrowserCookies();
        login(browser, "renewal-old");
        clock.advance(Duration.ofHours(2));
        MockHttpServletResponse delayed = perform(get(ME).cookie(browser.all()));
        assertThat(renewalCookies(delayed)).hasSize(1);
        login(browser, "renewal-new");

        clock.advance(Duration.ofHours(2));
        MockHttpServletResponse current = perform(get(ME).cookie(browser.all()));
        assertThat(current.getStatus()).isEqualTo(200);
        assertThat(renewalCookies(current)).hasSize(1);
        browser.accept(current);

        clock.advance(Duration.ofHours(2));
        browser.accept(delayed);

        MockHttpServletResponse response = perform(get(ME).cookie(browser.all()));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo(current.getContentAsString());
    }

    @Test
    void retainingOriginalCookieDoesNotExtendIdleAuthentication() throws Exception {
        BrowserCookies browser = new BrowserCookies();
        login(browser, "renewal-idle");
        clock.advance(Duration.ofMillis(ttlMs).plusSeconds(1));

        MockHttpServletResponse response = perform(get(ME).cookie(browser.all()));

        assertThat(browser.value(JwtCookie.NAME)).isNotBlank();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(renewalCookies(response)).isEmpty();
    }

    @Test
    void renewalCookieCannotSelectAnotherLogin() throws Exception {
        String expiredOriginal = loggedInAgo(Duration.ofHours(4));
        Clock past = Clock.offset(clock, Duration.ofHours(-4));
        String sessionId = new JwtProvider(secret, ttlMs, maxSessionMs, past)
                .parse(expiredOriginal).orElseThrow().sessionId();
        Cookie otherLogin = new Cookie(JwtCookie.RENEWAL_PREFIX + sessionId, loggedInAgo(Duration.ZERO));

        MockHttpServletResponse response = perform(get(ME).cookie(auth(expiredOriginal), otherLogin));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(renewalCookies(response)).isEmpty();
    }

    @Test
    void originalCookieSurvivesRenewalsButNotTheMaximumSession() throws Exception {
        BrowserCookies browser = new BrowserCookies();
        login(browser, "renewal-cap");
        String original = browser.value(JwtCookie.NAME);
        MockHttpServletResponse response = null;
        for (int hour = 2; hour <= 10; hour += 2) {
            clock.advance(Duration.ofHours(2));
            response = perform(get(ME).cookie(browser.all()));
            assertThat(response.getStatus()).isEqualTo(200);
            browser.accept(response);
            assertThat(browser.value(JwtCookie.NAME)).isEqualTo(original);
        }
        assertThat(renewalCookies(response)).singleElement()
                .satisfies(cookie -> assertThat(cookie).contains("Max-Age=7200"));

        clock.advance(Duration.ofHours(2).plusSeconds(1));
        MockHttpServletRequestBuilder request = get(ME);
        if (browser.all().length > 0) {
            request.cookie(browser.all());
        }
        assertThat(perform(request).getStatus()).isEqualTo(401);
    }

    @Test
    void switchingLoginClearsPreviouslyReceivedRenewalCookies() throws Exception {
        BrowserCookies browser = new BrowserCookies();
        login(browser, "renewal-old");
        clock.advance(Duration.ofHours(2));
        browser.accept(perform(get(ME).cookie(browser.all())));
        assertThat(browser.all()).anyMatch(cookie -> cookie.getName().startsWith(JwtCookie.RENEWAL_PREFIX));

        login(browser, "renewal-new");

        assertThat(browser.all()).noneMatch(cookie -> cookie.getName().startsWith(JwtCookie.RENEWAL_PREFIX));
        assertThat(perform(get(ME).cookie(browser.all())).getStatus()).isEqualTo(200);
    }

    @Test
    void freshTokenIsNotRenewed() throws Exception {
        MockHttpServletResponse response = perform(get(ME).cookie(auth(loggedInAgo(Duration.ZERO))));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(accessTokenCookies(response)).isEmpty();
    }

    @Test
    void tokenWithLessThanHalfLeftIsRenewedAndTheNewCookieWorks() throws Exception {
        String original = loggedInAgo(Duration.ofHours(2));
        MockHttpServletResponse response = perform(get(ME).cookie(auth(original)));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(accessTokenCookies(response)).isEmpty();
        assertThat(renewalCookies(response)).singleElement().satisfies(cookie -> assertThat(cookie)
                .contains("Max-Age=10800").contains("HttpOnly").contains("SameSite=Lax").contains("Path=/"));

        assertThat(perform(get(ME).cookie(auth(original), renewalCookie(response))).getStatus()).isEqualTo(200);
    }

    @Test
    void logoutDoesNotRenew() throws Exception {
        MockHttpServletResponse response = logout(post(LOGOUT), loggedInAgo(Duration.ofHours(2)));

        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(accessTokenCookies(response)).singleElement()
                .satisfies(cookie -> assertThat(cookie).contains("Max-Age=0"));
    }

    @Test
    void encodedLogoutPathDoesNotRenew() throws Exception {
        MockHttpServletResponse response =
                logout(post(URI.create("/api/v1/auth/%6Cogout")), loggedInAgo(Duration.ofHours(2)));

        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(accessTokenCookies(response)).singleElement()
                .satisfies(cookie -> assertThat(cookie).contains("Max-Age=0"));
    }

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

    @Test
    void renewedCookieArrivingAfterLogoutCannotBeUsed() throws Exception {
        String x = loggedInAgo(Duration.ofHours(2));
        Cookie y = renewalCookie(perform(get(ME).cookie(auth(x))));

        assertThat(logout(post(LOGOUT), x).getStatus()).isEqualTo(204);

        MockHttpServletResponse withY = perform(get(ME).cookie(auth(x), y));
        assertThat(withY.getStatus()).isEqualTo(401);
        assertThat(accessTokenCookies(withY)).isEmpty();
        assertThat(renewalCookies(withY)).isEmpty();
        assertThat(perform(get(ME).cookie(y)).getStatus()).isEqualTo(401);
        assertThat(perform(get(ME).cookie(auth(x))).getStatus()).isEqualTo(401);
    }

    @Test
    void renewedCookieCannotReviveLoginWhenOriginalTokenExpiresBeforeLogout() throws Exception {
        String x = loggedInAgo(Duration.ZERO);
        clock.advance(Duration.ofMillis(ttlMs).minusSeconds(1));
        MockHttpServletResponse renewal = perform(get(ME).cookie(auth(x)));
        assertThat(renewal.getStatus()).isEqualTo(200);
        Cookie y = renewalCookie(renewal);

        clock.advance(Duration.ofSeconds(2));
        assertThat(perform(get(ME).cookie(auth(x))).getStatus()).isEqualTo(401);
        assertThat(perform(get(ME).cookie(auth(x), y)).getStatus()).isEqualTo(200);
        String colleague = loggedInAgo(Duration.ZERO);

        MockHttpServletResponse response = logout(post(LOGOUT), x);
        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(accessTokenCookies(response)).singleElement()
                .satisfies(cookie -> assertThat(cookie).contains("Max-Age=0"));

        MockHttpServletResponse withY = perform(get(ME).cookie(auth(x), y));
        assertThat(withY.getStatus()).isEqualTo(401);
        assertThat(accessTokenCookies(withY)).isEmpty();
        assertThat(perform(get(ME).cookie(auth(colleague))).getStatus()).isEqualTo(200);
        assertThat(revokedSessionRepository.findAll()).singleElement()
                .extracting(RevokedSession::getUserId).isEqualTo(parent.getId());
    }

    @Test
    void logoutWithExpiredTokenStillRequiresCsrf() throws Exception {
        String expired = loggedInAgo(Duration.ofHours(4));

        MockHttpServletResponse response = perform(post(LOGOUT).cookie(auth(expired)));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(revokedSessionRepository.count()).isZero();
    }

    @Test
    void logoutWithExpiredTokenSignedWithAnotherKeyOnlyExpiresTheCookie() throws Exception {
        Clock past = Clock.offset(clock, Duration.ofHours(-4));
        String forged = new JwtProvider("another-secret-key-for-jwt-unit-test-99", ttlMs, maxSessionMs, past)
                .createToken(String.valueOf(parent.getId()));

        MockHttpServletResponse response = logout(post(LOGOUT), forged);

        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(accessTokenCookies(response)).singleElement()
                .satisfies(cookie -> assertThat(cookie).contains("Max-Age=0"));
        assertThat(revokedSessionRepository.count()).isZero();
    }

    @Test
    void logoutAfterMaximumSessionOnlyExpiresTheCookie() throws Exception {
        String token = loggedInAgo(Duration.ZERO);
        clock.advance(Duration.ofMillis(maxSessionMs));

        MockHttpServletResponse response = logout(post(LOGOUT), token);

        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(accessTokenCookies(response)).singleElement()
                .satisfies(cookie -> assertThat(cookie).contains("Max-Age=0"));
        assertThat(revokedSessionRepository.count()).isZero();
    }

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

    @Test
    void loggingOutWithALegacyTokenOnlyExpiresTheCookie() throws Exception {
        String legacy = Jwts.builder()
                .subject(String.valueOf(parent.getId()))
                .issuedAt(Date.from(clock.instant()))
                .expiration(Date.from(clock.instant().plus(Duration.ofHours(1))))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .compact();

        MockHttpServletResponse response = logout(post(LOGOUT), legacy);

        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(accessTokenCookies(response)).singleElement()
                .satisfies(cookie -> assertThat(cookie).contains("Max-Age=0"));
        assertThat(revokedSessionRepository.count()).isZero();
    }

    @Test
    void legacyTokenIsAcceptedButNotRenewed() throws Exception {
        String legacy = Jwts.builder()
                .subject(String.valueOf(parent.getId()))
                .issuedAt(Date.from(clock.instant().minus(Duration.ofHours(2))))
                .expiration(Date.from(clock.instant().plus(Duration.ofHours(1))))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .compact();

        MockHttpServletResponse response = perform(get(ME).cookie(auth(legacy)));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(accessTokenCookies(response)).isEmpty();
    }
}
