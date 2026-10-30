package com.itda.backend.service.sharing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jayway.jsonpath.JsonPath;
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentAction;
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;
import com.itda.backend.domain.user.User;
import com.itda.backend.dto.response.guardian.GuardianInsightResponse;
import com.itda.backend.dto.response.sharing.InboxDetailResponse;
import com.itda.backend.fixture.LockContention;
import com.itda.backend.fixture.LockContention.Held;
import com.itda.backend.fixture.OrganizationFixture;
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
import com.itda.backend.repository.user.UserRepository;
import com.itda.backend.service.consent.ConsentService;
import com.itda.backend.service.invitation.ChildGuardianService;

/**
 * 인사이트 단건 열람이 다른 열람·철회와 겹칠 때를 PostgreSQL 에서 재현한다 (#273). 기관 수신함과 보호자 단건이
 * 같은 열람 경로({@link SharingReadRecorder})를 쓰므로 둘 다 본다.
 *
 * <p>방식은 {@link LockContention} 이다. 먼저 가는 쪽은 바깥 트랜잭션 안에서 실제 서비스를 불러 <b>커밋하지 않고 잠금을 쥔 채</b> 멈춘다 — 서비스
 * 트랜잭션이 바깥에 합류한다. 뒤에 오는 쪽은 MockMvc 로 보내 보안 필터부터 거친다. 뒤의 요청이 먼저 쪽 때문에
 * 멈췄는지 {@code pg_blocking_pids} 로 확인한 뒤 먼저 쪽을 커밋하고, HTTP 응답과 DB 상태를 함께 본다.
 *
 * <p>정책: 철회가 먼저 반영되면 404 와 열람 시각 없음. 열람이 먼저면 200 과 열람 시각을 남긴 뒤 철회된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class SharingReadConcurrencyIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("itda_sharing_read_test").withUsername("test").withPassword("test");

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
    @Autowired private InstitutionInboxService institutionInboxService;
    @Autowired private GuardianInsightService guardianInsightService;
    @Autowired private ConsentService consentService;
    @Autowired private ChildGuardianService childGuardianService;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private ChildRepository childRepository;
    @Autowired private ChildOrganizationRepository childOrganizationRepository;
    @Autowired private ChildGuardianRepository childGuardianRepository;
    @Autowired private SharingConsentRepository sharingConsentRepository;
    @Autowired private InsightResultRepository insightResultRepository;
    @Autowired private SharingHistoryRepository sharingHistoryRepository;

    private Organization center;
    private User centerStaff;
    private User mom;
    private Long childId;
    private Long insightId;
    private Long centerGrantId;
    private Long momGrantId;

    private LockContention contention;

    @BeforeEach
    void setUp() {
        contention = new LockContention(transactionTemplate, entityManager, dataSource);
        Organization school = organizationRepository.save(
                Organization.of("햇살특수학교", OrganizationType.SCHOOL, OrganizationFixture.nextBusinessNumber()));
        center = organizationRepository.save(OrganizationFixture.center());
        centerStaff = userRepository.save(UserFixture.organizationUser("kakao-center", "센터선생", center.getId()));
        mom = userRepository.save(UserFixture.parent("kakao-mom", "엄마"));

        childId = childRepository.save(Child.of("임유진", LocalDate.of(2017, 3, 14))).getId();
        childOrganizationRepository.save(ChildOrganization.of(childId, school.getId(), "S-1"));
        childOrganizationRepository.save(ChildOrganization.of(childId, center.getId(), "C-7"));
        // 초대 코드 경로는 이 테스트의 관심이 아니다. 연결 행은 초대 id 만 있으면 된다.
        childGuardianRepository.save(ChildGuardian.link(childId, mom.getId(), 1L));
        sharingConsentRepository.save(SharingConsent.grant(childId, mom.getId(), school.getId(),
                List.of(ConsentField.WEEKLY_INSIGHT)));
        sharingConsentRepository.save(SharingConsent.grant(childId, mom.getId(), center.getId(),
                List.of(ConsentField.WEEKLY_INSIGHT)));

        insightId = insightResultRepository.save(InsightResult.of(childId, LocalDate.of(2026, 8, 17),
                LocalDate.of(2026, 8, 23), 1, "급식실 입장 직후", "소리를 지름", "이동 전 5분 예고가 효과적이었습니다.",
                null, "[]", "[]", "[%d]".formatted(school.getId()), false, null)).getId();
        // Gate 2 승인이 만든 것과 같은 모양 — 학교가 승인해 센터와 엄마에게 열었다.
        centerGrantId = sharingHistoryRepository.save(SharingHistory.grant(childId, insightId, school.getId(),
                ReceiverType.ORGANIZATION, center.getId())).getId();
        momGrantId = sharingHistoryRepository.save(SharingHistory.grant(childId, insightId, school.getId(),
                ReceiverType.GUARDIAN, mom.getId())).getId();
    }

    @AfterEach
    void cleanUp() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM sharing_history");
            statement.executeUpdate("DELETE FROM insight_result");
            statement.executeUpdate("DELETE FROM child_organization_request");
            statement.executeUpdate("DELETE FROM sharing_consent");
            statement.executeUpdate("DELETE FROM child_guardian");
            statement.executeUpdate("DELETE FROM child_organization");
            statement.executeUpdate("DELETE FROM child");
            statement.executeUpdate("DELETE FROM users");
            statement.executeUpdate("DELETE FROM organization");
        }
    }

    // --- 기관 수신함 ---

    /** 같은 권한 행의 열람 시각 UPDATE 끼리는 행 잠금에서 기다린다. 뒤의 UPDATE 는 조건을 다시 보고 0행이 된다. */
    @Test
    void 수신함_동시_열람은_처음_시각을_남기고_둘_다_그_시각을_돌려준다() throws Exception {
        Held<InboxDetailResponse> held = contention.firstHoldsThen(
                () -> institutionInboxService.getInboxItem(principal(centerStaff), insightId),
                () -> mockMvc.perform(get(inboxItem()).cookie(auth(centerStaff))).andReturn());

        LocalDateTime first = held.first().readAt();
        assertThat(held.second().getResponse().getStatus()).isEqualTo(200);
        assertThat(readAtOf(held.second())).isEqualTo(first);
        assertThat(grant(centerGrantId).getReadAt()).isEqualTo(first);
    }

    @Test
    void 수신함_철회가_먼저면_열람은_404_이고_열람_시각이_없다() throws Exception {
        Held<Object> held = contention.firstHoldsThen(
                () -> {
                    consentService.update(principal(mom), childId, center.getId(), ConsentAction.REVOKE, null);
                    return null;
                },
                () -> mockMvc.perform(get(inboxItem()).cookie(auth(centerStaff))).andReturn());

        assertNotFound(held.second());
        SharingHistory grant = grant(centerGrantId);
        assertThat(grant.isActive()).isFalse();
        assertThat(grant.getReadAt()).isNull();
    }

    @Test
    void 수신함_열람이_먼저면_200_과_열람_시각을_남긴_뒤_철회된다() throws Exception {
        Held<InboxDetailResponse> held = contention.firstHoldsThen(
                () -> institutionInboxService.getInboxItem(principal(centerStaff), insightId),
                () -> mockMvc.perform(withCsrf(put("/api/v1/children/" + childId + "/consent-scopes/"
                                + center.getId()), auth(mom))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"action\": \"revoke\"}"))
                        .andReturn());

        assertThat(held.first().content()).isEqualTo("이동 전 5분 예고가 효과적이었습니다.");
        assertThat(held.second().getResponse().getStatus()).isEqualTo(200);
        SharingHistory grant = grant(centerGrantId);
        assertThat(grant.getReadAt()).isEqualTo(held.first().readAt());
        assertThat(grant.isActive()).isFalse();
        assertNotFound(mockMvc.perform(get(inboxItem()).cookie(auth(centerStaff))).andReturn());
    }

    // --- 보호자 단건 ---

    @Test
    void 보호자_동시_열람은_처음_시각을_남기고_둘_다_그_시각을_돌려준다() throws Exception {
        Held<GuardianInsightResponse> held = contention.firstHoldsThen(
                () -> guardianInsightService.getInsight(principal(mom), insightId),
                () -> mockMvc.perform(get(guardianItem()).cookie(auth(mom))).andReturn());

        LocalDateTime first = held.first().readAt();
        assertThat(held.second().getResponse().getStatus()).isEqualTo(200);
        assertThat(readAtOf(held.second())).isEqualTo(first);
        assertThat(grant(momGrantId).getReadAt()).isEqualTo(first);
    }

    @Test
    void 보호자_연결_해제가_먼저면_열람은_404_이고_열람_시각이_없다() throws Exception {
        Held<Object> held = contention.firstHoldsThen(
                () -> {
                    childGuardianService.unlinkBySelf(principal(mom), childId);
                    return null;
                },
                () -> mockMvc.perform(get(guardianItem()).cookie(auth(mom))).andReturn());

        assertNotFound(held.second());
        SharingHistory grant = grant(momGrantId);
        assertThat(grant.isActive()).isFalse();
        assertThat(grant.getReadAt()).isNull();
    }

    @Test
    void 보호자_열람이_먼저면_200_과_열람_시각을_남긴_뒤_연결이_끊긴다() throws Exception {
        Held<GuardianInsightResponse> held = contention.firstHoldsThen(
                () -> guardianInsightService.getInsight(principal(mom), insightId),
                () -> mockMvc.perform(withCsrf(delete("/api/v1/guardians/me/children/" + childId), auth(mom)))
                        .andReturn());

        assertThat(held.first().content()).isEqualTo("이동 전 5분 예고가 효과적이었습니다.");
        assertThat(held.second().getResponse().getStatus()).isEqualTo(204);
        SharingHistory grant = grant(momGrantId);
        assertThat(grant.getReadAt()).isEqualTo(held.first().readAt());
        assertThat(grant.isActive()).isFalse();
    }

    // --- 도구 ---

    private SharingHistory grant(Long grantId) {
        return sharingHistoryRepository.findById(grantId).orElseThrow();
    }

    private static LocalDateTime readAtOf(MvcResult result) throws Exception {
        String readAt = JsonPath.read(result.getResponse().getContentAsString(), "$.data.readAt");
        return LocalDateTime.parse(readAt);
    }

    private static void assertNotFound(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        String body = result.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.code")).isEqualTo("SHARING_NOT_FOUND");
    }

    private String inboxItem() {
        return "/api/v1/institutions/me/inbox/" + insightId;
    }

    private String guardianItem() {
        return "/api/v1/guardians/me/insights/" + insightId;
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
}
