package com.itda.backend.service.insight;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.itda.backend.domain.approval.Gate2Action;
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;
import com.itda.backend.domain.user.User;
import com.itda.backend.fixture.LockContention;
import com.itda.backend.fixture.LockContention.Held;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.TermsFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.consent.SharingConsentRepository;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.invitation.ChildGuardianRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.sharing.SharingHistoryRepository;
import com.itda.backend.repository.user.TermsAgreementRepository;
import com.itda.backend.repository.user.UserRepository;
import com.itda.backend.service.invitation.InvitationService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class GuardianLinkInsightConcurrencyIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("itda_guardian_link_insight_test").withUsername("test").withPassword("test");

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
    @Autowired private InsightGate2Service insightGate2Service;
    @Autowired private InvitationService invitationService;
    @Autowired private UserRepository userRepository;
    @Autowired private TermsAgreementRepository termsAgreementRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private ChildRepository childRepository;
    @Autowired private ChildOrganizationRepository childOrganizationRepository;
    @Autowired private ChildGuardianRepository childGuardianRepository;
    @Autowired private SharingConsentRepository sharingConsentRepository;
    @Autowired private InsightResultRepository insightResultRepository;
    @Autowired private SharingHistoryRepository sharingHistoryRepository;

    private Organization school;
    private User schoolStaff;
    private User mom;
    private User dad;
    private Long childId;
    private Long insightId;

    private LockContention contention;

    @BeforeEach
    void setUp() {
        contention = new LockContention(transactionTemplate, entityManager, dataSource);
        school = organizationRepository.save(
                Organization.of("햇살특수학교", OrganizationType.SCHOOL, OrganizationFixture.nextBusinessNumber()));
        schoolStaff = TermsFixture.agreeAll(termsAgreementRepository, userRepository.save(UserFixture.organizationUser("kakao-school", school.getId())));
        mom = TermsFixture.agreeAll(termsAgreementRepository, userRepository.save(UserFixture.parent("kakao-mom")));
        dad = TermsFixture.agreeAll(termsAgreementRepository, userRepository.save(UserFixture.parent("kakao-dad")));

        childId = childRepository.save(Child.of("임유진", LocalDate.of(2017, 3, 14))).getId();
        childOrganizationRepository.save(ChildOrganization.of(childId, school.getId(), "S-1"));
        childGuardianRepository.save(ChildGuardian.link(childId, mom.getId(), 1L));
        sharingConsentRepository.save(SharingConsent.grant(childId, mom.getId(), school.getId(),
                List.of(ConsentField.WEEKLY_INSIGHT)));

        insightId = insightResultRepository.save(InsightResult.of(childId, LocalDate.of(2026, 8, 17),
                LocalDate.of(2026, 8, 23), 1, "급식실 입장 직후", "소리를 지름", "이동 전 5분 예고가 효과적이었습니다.",
                null, "[]", "[]", "[%d]".formatted(school.getId()), false, null)).getId();
    }

    @AfterEach
    void cleanUp() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM sharing_history");
            statement.executeUpdate("DELETE FROM human_review");
            statement.executeUpdate("DELETE FROM insight_result");
            statement.executeUpdate("DELETE FROM sharing_consent");
            statement.executeUpdate("DELETE FROM child_guardian");
            statement.executeUpdate("DELETE FROM invitation");
            statement.executeUpdate("DELETE FROM child_organization");
            statement.executeUpdate("DELETE FROM child");
            statement.executeUpdate("DELETE FROM terms_agreement");
            statement.executeUpdate("DELETE FROM users");
            statement.executeUpdate("DELETE FROM organization");
        }
    }

    @Test
    void 승인이_먼저면_수락이_기다렸다가_아빠에게_연다() throws Exception {
        String code = invitationService.issue(school.getId(), childId).code();

        Held<Object> held = contention.firstHoldsThen(
                () -> insightGate2Service.decide(principal(schoolStaff), insightId, Gate2Action.APPROVE),
                () -> mockMvc.perform(withCsrf(post("/api/v1/guardians/me/invitations/accept"), auth(dad))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"code\": \"%s\"}".formatted(code)))
                        .andReturn());

        assertThat(held.second().getResponse().getStatus()).isEqualTo(200);
        assertThat(guardianGrants(dad)).singleElement().satisfies(grant -> {
            assertThat(grant.isActive()).isTrue();
            assertThat(grant.getSenderOrganizationId()).isEqualTo(school.getId());
        });
        assertThat(guardianGrants(mom)).hasSize(1);
    }

    @Test
    void 수락이_먼저면_승인이_기다렸다가_아빠까지_연다() throws Exception {
        String code = invitationService.issue(school.getId(), childId).code();

        Held<Object> held = contention.firstHoldsThen(
                () -> invitationService.accept(principal(dad), code),
                () -> mockMvc.perform(withCsrf(post("/api/v1/insights/" + insightId + "/gate2"), auth(schoolStaff))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"decision\": \"approve\"}"))
                        .andReturn());

        assertThat(held.second().getResponse().getStatus()).isEqualTo(200);
        assertThat(guardianGrants(dad)).singleElement()
                .satisfies(grant -> assertThat(grant.isActive()).isTrue());
        assertThat(guardianGrants(mom)).hasSize(1);
    }

    private List<SharingHistory> guardianGrants(User guardian) {
        return sharesOf(insightId).stream()
                .filter(grant -> grant.getReceiverType() == ReceiverType.GUARDIAN
                        && grant.getReceiverId().equals(guardian.getId()))
                .toList();
    }

    private static String principal(User user) {
        return String.valueOf(user.getId());
    }

    private Cookie auth(User user) {
        return new Cookie(JwtCookie.NAME, jwtProvider.createToken(principal(user)));
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request, Cookie... cookies)
            throws Exception {
        Cookie csrf = mockMvc.perform(get("/api/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        Cookie[] all = new Cookie[cookies.length + 1];
        System.arraycopy(cookies, 0, all, 0, cookies.length);
        all[cookies.length] = csrf;
        return request.cookie(all).header("X-XSRF-TOKEN", csrf.getValue());
    }

    private List<SharingHistory> sharesOf(Long insightResultId) {
        return sharingHistoryRepository.findAll().stream()
                .filter(history -> history.getInsightResultId().equals(insightResultId))
                .toList();
    }
}
