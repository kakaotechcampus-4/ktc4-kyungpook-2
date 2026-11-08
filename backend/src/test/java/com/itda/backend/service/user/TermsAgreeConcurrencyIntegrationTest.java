package com.itda.backend.service.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.itda.backend.domain.user.TermKey;
import com.itda.backend.domain.user.TermsAgreement;
import com.itda.backend.domain.user.User;
import com.itda.backend.dto.response.user.TermsAgreementResponse;
import com.itda.backend.fixture.LockContention;
import com.itda.backend.fixture.LockContention.Held;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.user.TermsAgreementRepository;
import com.itda.backend.repository.user.UserRepository;

/**
 * 같은 보호자의 약관 동의가 겹칠 때를 PostgreSQL 에서 재현한다. 방식은 {@link LockContention} 이다.
 *
 * <p>정책: 동의는 회원 행에서 줄을 선다. 뒤에 온 쪽은 앞쪽이 커밋한 줄을 보고 아무것도 넣지 않는다 — 둘 다 성공하고
 * 약관마다 줄은 하나다. UNIQUE 위반으로 끝나면(잠금을 거치지 않으면) 뒤의 요청이 500 이 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class TermsAgreeConcurrencyIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("itda_terms_agree_test").withUsername("test").withPassword("test");

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
    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private TermsAgreementRepository termsAgreementRepository;

    private LockContention contention;
    private User parent;

    @BeforeEach
    void setUp() {
        contention = new LockContention(transactionTemplate, entityManager, dataSource);
        parent = userRepository.save(UserFixture.parent("kakao-terms-parent", "김보호"));
    }

    @AfterEach
    void cleanUp() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM terms_agreement");
            statement.executeUpdate("DELETE FROM users");
        }
    }

    @Test
    void 같은_보호자의_동의가_겹치면_줄을_서고_둘_다_성공하며_약관마다_한_줄이다() throws Exception {
        String principal = String.valueOf(parent.getId());
        Cookie auth = new Cookie(JwtCookie.NAME, jwtProvider.createToken(principal));
        Cookie csrf = mockMvc.perform(get("/api/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");

        Held<TermsAgreementResponse> held = contention.firstHoldsThen(
                () -> userService.agreeTerms(principal, List.of(TermKey.TOS, TermKey.PRIVACY), null),
                () -> mockMvc.perform(post("/api/v1/auth/terms")
                                .cookie(auth, csrf)
                                .header("X-XSRF-TOKEN", csrf.getValue())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"agreed\": [\"tos\", \"privacy\"]}"))
                        .andReturn());

        assertThat(held.first().termsAgreed()).isTrue();
        assertThat(held.second().getResponse().getStatus()).isEqualTo(200);
        assertThat(held.second().getResponse().getContentAsString()).contains("\"termsAgreed\":true");
        assertThat(termsAgreementRepository.findByUserId(parent.getId()))
                .extracting(TermsAgreement::getTermKey)
                .containsExactlyInAnyOrder(TermKey.TOS, TermKey.PRIVACY);
    }
}
