package com.itda.backend.service.checklist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.itda.backend.domain.checklist.Checklist;
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.user.User;
import com.itda.backend.fixture.LockContention;
import com.itda.backend.fixture.LockContention.Held;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.TermsFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.checklist.ChecklistRepository;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.user.TermsAgreementRepository;
import com.itda.backend.repository.user.UserRepository;

/**
 * 같은 요청을 두 번 동시에 내릴 때를 PostgreSQL 에서 재현한다. 방식은 {@link LockContention} 이다.
 *
 * <p>정책: 내리기는 조건부 UPDATE 라 뒤의 요청이 앞선 커밋을 기다렸다 조건을 다시 보고 0행이 된다. 둘 다 204 이고 내린 시각은
 * 먼저 커밋한 쪽 값이다. 엔티티를 읽어 고쳐 저장하면 둘 다 "아직 안 내림" 을 보고 나중 것이 처음 시각을 덮는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class ChecklistCancelConcurrencyIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("itda_checklist_cancel_test").withUsername("test").withPassword("test");

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
    @Autowired private ChecklistService checklistService;
    @Autowired private ChecklistRepository checklistRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TermsAgreementRepository termsAgreementRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private ChildRepository childRepository;
    @Autowired private ChildOrganizationRepository childOrganizationRepository;

    private LockContention contention;
    private User staff;
    private Long checklistId;

    @BeforeEach
    void setUp() {
        contention = new LockContention(transactionTemplate, entityManager, dataSource);
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        staff = TermsFixture.agreeAll(termsAgreementRepository, userRepository.save(UserFixture.organizationUser("kakao-staff", "이선생", organization.getId())));
        Child sky = childRepository.save(Child.of("하늘", LocalDate.of(2017, 3, 14)));
        childOrganizationRepository.save(ChildOrganization.of(sky.getId(), organization.getId()));
        checklistId = checklistService.send(String.valueOf(staff.getId()), List.of("실내화를 새로 준비해주세요."),
                List.of(sky.getId())).id().transform(Long::valueOf);
    }

    @AfterEach
    void cleanUp() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM checklist_target");
            statement.executeUpdate("DELETE FROM checklist");
            statement.executeUpdate("DELETE FROM child_organization");
            statement.executeUpdate("DELETE FROM child");
            statement.executeUpdate("DELETE FROM terms_agreement");
            statement.executeUpdate("DELETE FROM users");
            statement.executeUpdate("DELETE FROM organization");
        }
    }

    @Test
    void 두_내리기가_겹치면_둘_다_204이고_먼저_커밋한_시각이_남는다() throws Exception {
        Cookie auth = new Cookie(JwtCookie.NAME, jwtProvider.createToken(String.valueOf(staff.getId())));
        Cookie csrf = mockMvc.perform(get("/api/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");

        Held<LocalDateTime> held = contention.firstHoldsThen(
                () -> {
                    checklistService.cancel(String.valueOf(staff.getId()), checklistId);
                    return ((Timestamp) entityManager
                            .createNativeQuery("SELECT canceled_at FROM checklist WHERE id = :id")
                            .setParameter("id", checklistId).getSingleResult()).toLocalDateTime();
                },
                () -> mockMvc.perform(delete("/api/v1/institutions/me/checklists/" + checklistId)
                                .cookie(auth, csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                        .andReturn());

        assertThat(held.second().getResponse().getStatus()).isEqualTo(204);
        Checklist stored = checklistRepository.findById(checklistId).orElseThrow();
        assertThat(stored.getCanceledAt()).isEqualTo(held.first());
    }
}
