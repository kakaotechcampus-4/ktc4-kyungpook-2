package com.itda.backend.service.checklist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.itda.backend.domain.checklist.ChecklistTarget;
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.user.User;
import com.itda.backend.dto.response.checklist.GuardianChecklistResponse;
import com.itda.backend.fixture.LockContention;
import com.itda.backend.fixture.LockContention.Held;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.TermsFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.checklist.ChecklistRepository;
import com.itda.backend.repository.checklist.ChecklistTargetRepository;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.invitation.ChildGuardianRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.user.TermsAgreementRepository;
import com.itda.backend.repository.user.UserRepository;

/**
 * 확인이 다른 확인 · 내리기와 겹칠 때를 PostgreSQL 에서 재현한다. 방식은 {@link LockContention} 이다 — 먼저 쪽을 커밋 직전에
 * 멈추고, 뒤의 요청이 그 잠금에서 실제로 기다린 뒤에 먼저 쪽을 커밋한다.
 *
 * <p>정책: 확인끼리는 받은 줄({@code checklist_target}) UPDATE 에서 줄을 서고 먼저 한 사람만 남는다. 확인과 내리기는 요청 행
 * ({@code checklist})에서 줄을 선다 — 확인은 공유 잠금, 내리기는 UPDATE. 내리기가 먼저면 확인은 404, 확인이 먼저면 확인이
 * 남고 내리기는 그 뒤에 204 다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class ChecklistConfirmConcurrencyIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("itda_checklist_confirm_test").withUsername("test").withPassword("test");

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
    @Autowired private GuardianChecklistService guardianChecklistService;
    @Autowired private ChecklistRepository checklistRepository;
    @Autowired private ChecklistTargetRepository checklistTargetRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TermsAgreementRepository termsAgreementRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private ChildRepository childRepository;
    @Autowired private ChildOrganizationRepository childOrganizationRepository;
    @Autowired private ChildGuardianRepository childGuardianRepository;

    private LockContention contention;
    private User staff;
    private User mom;
    private User dad;
    private Long childId;
    private Long checklistId;

    @BeforeEach
    void setUp() {
        contention = new LockContention(transactionTemplate, entityManager, dataSource);
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        staff = userRepository.save(UserFixture.organizationUser("kakao-staff", "이선생", organization.getId()));
        mom = TermsFixture.agreeAll(termsAgreementRepository, userRepository.save(UserFixture.parent("kakao-mom", "엄마")));
        dad = TermsFixture.agreeAll(termsAgreementRepository, userRepository.save(UserFixture.parent("kakao-dad", "아빠")));
        Child sky = childRepository.save(Child.of("하늘", LocalDate.of(2017, 3, 14)));
        childId = sky.getId();
        childOrganizationRepository.save(ChildOrganization.of(childId, organization.getId()));
        childGuardianRepository.save(ChildGuardian.link(childId, mom.getId(), 1L));
        childGuardianRepository.save(ChildGuardian.link(childId, dad.getId(), 1L));
        checklistId = Long.valueOf(checklistService.send(principal(staff), List.of("실내화를 새로 준비해주세요."),
                List.of(childId)).id());
    }

    @AfterEach
    void cleanUp() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM checklist_target");
            statement.executeUpdate("DELETE FROM checklist");
            statement.executeUpdate("DELETE FROM child_guardian");
            statement.executeUpdate("DELETE FROM child_organization");
            statement.executeUpdate("DELETE FROM child");
            statement.executeUpdate("DELETE FROM terms_agreement");
            statement.executeUpdate("DELETE FROM users");
            statement.executeUpdate("DELETE FROM organization");
        }
    }

    @Test
    void 엄마_아빠가_겹쳐_확인하면_둘_다_200이고_엄마가_남는다() throws Exception {
        Held<GuardianChecklistResponse> held = contention.firstHoldsThen(
                () -> guardianChecklistService.confirm(principal(mom), childId, checklistId),
                () -> mockMvc.perform(withCsrf(post(confirmPath()), dad)).andReturn());

        assertThat(held.first().confirmedByName()).isEqualTo("엄마");
        assertThat(held.second().getResponse().getStatus()).isEqualTo(200);
        assertThat(held.second().getResponse().getContentAsString()).contains("\"confirmedByName\":\"엄마\"");
        assertThat(target().getConfirmedBy()).isEqualTo(mom.getId());
    }

    @Test
    void 내리기가_먼저면_겹친_확인은_404이고_확인이_남지_않는다() throws Exception {
        Held<Object> held = contention.firstHoldsThen(
                () -> {
                    checklistService.cancel(principal(staff), checklistId);
                    return null;
                },
                () -> mockMvc.perform(withCsrf(post(confirmPath()), mom)).andReturn());

        assertThat(held.second().getResponse().getStatus()).isEqualTo(404);
        assertThat(held.second().getResponse().getContentAsString()).contains("\"code\":\"CHECKLIST_NOT_FOUND\"");
        assertThat(target().isConfirmed()).isFalse();
        assertThat(checklistRepository.findById(checklistId).orElseThrow().isCanceled()).isTrue();
    }

    @Test
    void 확인이_먼저면_확인이_남고_겹친_내리기는_그_뒤에_204다() throws Exception {
        Held<GuardianChecklistResponse> held = contention.firstHoldsThen(
                () -> guardianChecklistService.confirm(principal(mom), childId, checklistId),
                () -> mockMvc.perform(withCsrf(delete("/api/v1/institutions/me/checklists/" + checklistId), staff))
                        .andReturn());

        assertThat(held.first().status()).isEqualTo("confirmed");
        assertThat(held.second().getResponse().getStatus()).isEqualTo(204);
        assertThat(target().getConfirmedBy()).isEqualTo(mom.getId());
        assertThat(checklistRepository.findById(checklistId).orElseThrow().isCanceled()).isTrue();
    }

    // --- 도구 ---

    private ChecklistTarget target() {
        return checklistTargetRepository.findByChecklistIdIn(List.of(checklistId)).get(0);
    }

    private String confirmPath() {
        return "/api/v1/children/" + childId + "/checklists/" + checklistId + "/confirm";
    }

    private static String principal(User user) {
        return String.valueOf(user.getId());
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request, User user) throws Exception {
        Cookie csrf = mockMvc.perform(get("/api/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        return request.cookie(new Cookie(JwtCookie.NAME, jwtProvider.createToken(principal(user))), csrf)
                .header("X-XSRF-TOKEN", csrf.getValue());
    }
}
