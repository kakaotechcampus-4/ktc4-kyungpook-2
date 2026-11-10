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
import com.itda.backend.service.invitation.ChildGuardianService;

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
    @Autowired private ChildGuardianService childGuardianService;

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
        staff = TermsFixture.agreeAll(termsAgreementRepository, userRepository.save(UserFixture.organizationUser("kakao-staff", organization.getId())));
        mom = TermsFixture.agreeAll(termsAgreementRepository, userRepository.save(UserFixture.parent("kakao-mom")));
        dad = TermsFixture.agreeAll(termsAgreementRepository, userRepository.save(UserFixture.parent("kakao-dad")));
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
            statement.executeUpdate("DELETE FROM sharing_consent");
            statement.executeUpdate("DELETE FROM child_organization_request");
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

        assertThat(held.first().status()).isEqualTo("confirmed");
        assertThat(held.second().getResponse().getStatus()).isEqualTo(200);
        assertThat(held.second().getResponse().getContentAsString()).contains("\"status\":\"confirmed\"");
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

    @Test
    void 연결_해제가_먼저면_겹친_확인은_403이고_확인이_남지_않는다() throws Exception {
        Held<Object> held = contention.firstHoldsThen(
                () -> {
                    childGuardianService.unlinkBySelf(principal(dad), childId);
                    return null;
                },
                () -> mockMvc.perform(withCsrf(post(confirmPath()), dad)).andReturn());

        assertThat(held.second().getResponse().getStatus()).isEqualTo(403);
        assertThat(held.second().getResponse().getContentAsString()).contains("\"code\":\"FORBIDDEN\"");
        assertThat(target().isConfirmed()).isFalse();
    }

    @Test
    void 확인이_먼저면_확인이_남고_겹친_연결_해제는_그_뒤에_204다() throws Exception {
        Held<GuardianChecklistResponse> held = contention.firstHoldsThen(
                () -> guardianChecklistService.confirm(principal(dad), childId, checklistId),
                () -> mockMvc.perform(withCsrf(delete("/api/v1/guardians/me/children/" + childId), dad)).andReturn());

        assertThat(held.first().status()).isEqualTo("confirmed");
        assertThat(held.second().getResponse().getStatus()).isEqualTo(204);
        assertThat(target().getConfirmedBy()).isEqualTo(dad.getId());
        assertThat(childGuardianRepository.existsByChildIdAndUserIdAndDeletedAtIsNull(childId, dad.getId())).isFalse();
    }

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
