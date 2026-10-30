package com.itda.backend.service.child;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jayway.jsonpath.JsonPath;
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.summary.ChildContext;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.domain.user.User;
import com.itda.backend.dto.response.child.GuardianJournalResponse;
import com.itda.backend.fixture.LockContention;
import com.itda.backend.fixture.LockContention.Held;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.invitation.ChildGuardianRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.summary.ChildContextRepository;
import com.itda.backend.repository.summary.SummaryResultRepository;
import com.itda.backend.repository.user.UserRepository;
import com.itda.backend.service.invitation.ChildGuardianService;

/**
 * 보호자 일지 상세가 보호자 연결 해제와 겹칠 때를 PostgreSQL 에서 재현한다. 상세는 인사이트 열람 도우미
 * ({@code SharingReadRecorder})를 거치지 않고 아이 행을 직접 공유 잠금으로 잡으므로 따로 본다 — 잠금을 빠뜨리거나
 * 잠그기 전에 연결을 읽어도 인사이트 쪽 테스트는 통과한다.
 *
 * <p>방식은 {@link LockContention} 이다. 먼저 가는 쪽은 바깥 트랜잭션 안에서 실제 서비스를
 * 불러 <b>커밋하지 않고 잠금을 쥔 채</b> 멈춘다. 뒤에 오는 쪽은 MockMvc 로 보내 보안 필터부터 거친다. 뒤의 요청이 먼저
 * 쪽 때문에 멈췄는지 {@code pg_blocking_pids} 로 확인한 뒤 먼저 쪽을 커밋하고, HTTP 응답과 DB 상태를 함께 본다.
 *
 * <p>정책: 해제가 먼저 반영되면 404. 열람이 먼저면 200 을 돌려준 뒤 해제된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class GuardianJournalConcurrencyIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("itda_guardian_journal_test").withUsername("test").withPassword("test");

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
    @Autowired private GuardianJournalQueryService guardianJournalQueryService;
    @Autowired private ChildGuardianService childGuardianService;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private ChildRepository childRepository;
    @Autowired private ChildOrganizationRepository childOrganizationRepository;
    @Autowired private ChildGuardianRepository childGuardianRepository;
    @Autowired private SummaryResultRepository summaryResultRepository;
    @Autowired private ChildContextRepository childContextRepository;

    private User mom;
    private Long childId;
    private Long journalId;

    private LockContention contention;

    @BeforeEach
    void setUp() {
        contention = new LockContention(transactionTemplate, entityManager, dataSource);
        Organization center = organizationRepository.save(OrganizationFixture.center());
        mom = userRepository.save(UserFixture.parent("kakao-mom", "엄마"));

        childId = childRepository.save(Child.of("임유진", LocalDate.of(2017, 3, 14))).getId();
        childOrganizationRepository.save(ChildOrganization.of(childId, center.getId(), "C-7"));
        // 초대 코드 경로는 이 테스트의 관심이 아니다. 연결 행은 초대 id 만 있으면 된다.
        childGuardianRepository.save(ChildGuardian.link(childId, mom.getId(), 1L));

        // Gate 1 승인이 만든 것과 같은 모양 — 센터가 요약을 승인했다.
        SummaryResult summary = summaryResultRepository.save(SummaryResult.of(childId, LocalDate.of(2026, 8, 21),
                center.getId(), 1, "오후 미술 활동에 끝까지 참여했어요.", "[]", "[]", "[]", "{}"));
        journalId = childContextRepository.save(
                ChildContext.of(childId, summary.getId(), "오후 미술 활동에 끝까지 참여했어요.")).getId();
    }

    @AfterEach
    void cleanUp() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM child_context");
            statement.executeUpdate("DELETE FROM summary_result");
            statement.executeUpdate("DELETE FROM child_organization_request");
            statement.executeUpdate("DELETE FROM sharing_consent");
            statement.executeUpdate("DELETE FROM child_guardian");
            statement.executeUpdate("DELETE FROM child_organization");
            statement.executeUpdate("DELETE FROM child");
            statement.executeUpdate("DELETE FROM users");
            statement.executeUpdate("DELETE FROM organization");
        }
    }

    @Test
    void 연결_해제가_먼저면_일지_상세는_404_JOURNAL_NOT_FOUND() throws Exception {
        Held<Object> held = contention.firstHoldsThen(
                () -> {
                    childGuardianService.unlinkBySelf(principal(mom), childId);
                    return null;
                },
                () -> mockMvc.perform(get(entryPath()).cookie(auth(mom))).andReturn());

        assertNotFound(held.second());
    }

    @Test
    void 일지_상세가_먼저면_200_뒤에_연결이_끊긴다() throws Exception {
        Held<GuardianJournalResponse> held = contention.firstHoldsThen(
                () -> guardianJournalQueryService.entry(principal(mom), journalId),
                () -> mockMvc.perform(withCsrf(delete("/api/v1/guardians/me/children/" + childId), auth(mom)))
                        .andReturn());

        assertThat(held.first().content()).isEqualTo("오후 미술 활동에 끝까지 참여했어요.");
        assertThat(held.second().getResponse().getStatus()).isEqualTo(204);
        assertNotFound(mockMvc.perform(get(entryPath()).cookie(auth(mom))).andReturn());
    }

    // --- 도구 ---

    private static void assertNotFound(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        String body = result.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.code")).isEqualTo("JOURNAL_NOT_FOUND");
    }

    private String entryPath() {
        return "/api/v1/journal/" + journalId;
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
