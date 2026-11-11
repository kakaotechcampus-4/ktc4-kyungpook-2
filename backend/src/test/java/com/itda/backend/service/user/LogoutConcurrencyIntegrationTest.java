package com.itda.backend.service.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.itda.backend.domain.user.RevokedSession;
import com.itda.backend.domain.user.User;
import com.itda.backend.fixture.LockContention;
import com.itda.backend.fixture.LockContention.Held;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.AccessToken;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.user.RevokedSessionRepository;
import com.itda.backend.repository.user.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class LogoutConcurrencyIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("itda_logout_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private EntityManager entityManager;
    @Autowired private DataSource dataSource;
    @Autowired private UserRepository userRepository;
    @Autowired private RevokedSessionRepository revokedSessionRepository;
    @Autowired private RevokedSessionService revokedSessionService;

    private LockContention contention;
    private User parent;

    @BeforeEach
    void setUp() {
        contention = new LockContention(transactionTemplate, entityManager, dataSource);
        parent = userRepository.save(UserFixture.parent("kakao-logout-parent"));
    }

    @AfterEach
    void cleanUp() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM revoked_session");
            statement.executeUpdate("DELETE FROM users");
        }
    }

    @Test
    void 같은_로그인의_로그아웃이_겹치면_둘_다_성공하고_기록은_한_줄이다() throws Exception {
        String token = jwtProvider.createToken(String.valueOf(parent.getId()));
        AccessToken parsed = jwtProvider.parse(token).orElseThrow();
        Cookie auth = new Cookie(JwtCookie.NAME, token);
        Cookie csrf = mockMvc.perform(get("/api/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");

        Held<RevokedSession> held = contention.firstHoldsThen(
                () -> revokedSessionRepository.saveAndFlush(RevokedSession.of(parsed.sessionId(), parent.getId())),
                () -> mockMvc.perform(post("/api/v1/auth/logout")
                                .cookie(auth, csrf)
                                .header("X-XSRF-TOKEN", csrf.getValue()))
                        .andReturn());

        assertThat(held.second().getResponse().getStatus()).isEqualTo(204);
        assertThat(revokedSessionRepository.findAll()).singleElement()
                .extracting(RevokedSession::getSessionId).isEqualTo(parsed.sessionId());
    }

    @Test
    void 겹친_로그아웃_기록은_예외_없이_끝난다() throws Exception {
        String sessionId = jwtProvider.parse(jwtProvider.createToken(String.valueOf(parent.getId())))
                .orElseThrow().sessionId();

        contention.firstHoldsThen(
                () -> revokedSessionRepository.saveAndFlush(RevokedSession.of(sessionId, parent.getId())),
                () -> {
                    revokedSessionService.revoke(sessionId, parent.getId());
                    return null;
                });

        assertThat(revokedSessionRepository.findAll()).singleElement()
                .extracting(RevokedSession::getSessionId).isEqualTo(sessionId);
    }
}
