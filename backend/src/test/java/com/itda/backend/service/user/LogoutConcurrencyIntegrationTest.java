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

/**
 * 같은 로그인의 로그아웃이 겹칠 때를 PostgreSQL 에서 재현한다. 방식은 {@link LockContention} 이다 — 먼저 온 로그아웃이
 * 기록을 넣고 커밋하지 않은 채 멈추면, 뒤의 로그아웃은 "아직 없음" 을 보고 넣다가 UNIQUE 인덱스에서 기다린다.
 *
 * <p>정책: 뒤의 로그아웃은 먼저 쪽이 커밋된 뒤 UNIQUE 위반을 받는데, 그 저장 트랜잭션만 롤백되고 바깥에서 "이미 기록됨" 으로
 * 넘긴다 — 둘 다 성공하고 기록은 한 줄이다. 같은 트랜잭션 안에서 위반을 잡았다면 커밋 때 실패해 500 이 된다.
 */
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

    /**
     * 로그아웃 API 는 기록에 실패해도 쿠키를 지우고 204 를 낸다. 그래서 위 시험만으로는 기록이 예외 없이 끝났는지 알 수 없다 —
     * 같은 경합을 서비스에 직접 걸어 예외가 나지 않는지 본다(예외가 나면 뒤 쪽 작업이 실패로 끝난다).
     */
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
