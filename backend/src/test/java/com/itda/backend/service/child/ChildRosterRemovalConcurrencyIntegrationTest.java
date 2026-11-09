package com.itda.backend.service.child;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.BDDMockito.given;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.invitation.Invitation;
import com.itda.backend.domain.invitation.InvitationStatus;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.user.User;
import com.itda.backend.exception.child.ChildException;
import com.itda.backend.exception.invitation.InvitationException;
import com.itda.backend.fixture.MutableClock;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.invitation.ChildGuardianRepository;
import com.itda.backend.repository.invitation.InvitationRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.user.UserRepository;
import com.itda.backend.service.invitation.InvitationService;
import com.itda.backend.service.user.UserService;

/**
 * 명부 제외(O-63)가 초대 코드 추가 발급(O-15)·수락(G-04)과 겹칠 때를 PostgreSQL 에서 재현한다.
 *
 * <p>홀더가 아이 행을 잠근 채 있는 동안 작업을 하나씩 보내 줄을 세운다. 먼저 줄을 선 작업이 먼저 잠금을 얻는다.
 * 어느 순서든 "명부에 없는 아이에게 쓸 수 있는 코드" 나 "명부 제외로 사라진 보호자 연결" 이 남으면 안 된다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
@Import(ChildRosterRemovalConcurrencyIntegrationTest.ClockConfig.class)
class ChildRosterRemovalConcurrencyIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 9, 10, 0);
    private static final String STAFF = "u-staff";
    private static final String MOTHER = "u-mother";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("itda_roster_removal_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired private ChildService childService;
    @Autowired private InvitationService invitationService;
    @Autowired private ChildRepository childRepository;
    @Autowired private ChildOrganizationRepository childOrganizationRepository;
    @Autowired private ChildGuardianRepository childGuardianRepository;
    @Autowired private InvitationRepository invitationRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MutableClock clock;
    @Autowired private DataSource dataSource;

    @MockitoBean private UserService userService;

    private Organization organization;
    private Child child;
    private Long linkId;

    @BeforeEach
    void setUp() {
        clock.setTo(NOW);
        organization = organizationRepository.save(OrganizationFixture.center());
        child = childRepository.save(Child.of("김하늘", LocalDate.of(2017, 3, 14)));
        linkId = childOrganizationRepository.save(ChildOrganization.of(child.getId(), organization.getId(), "C-1"))
                .getId();
        User mother = userRepository.save(UserFixture.parent("kakao-mother"));
        given(userService.getOrganizationIdOf(STAFF)).willReturn(organization.getId());
        given(userService.getUserIdOf(STAFF)).willReturn(777L);
        given(userService.getParentIdOf(MOTHER)).willReturn(mother.getId());
    }

    @AfterEach
    void cleanUp() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM sharing_history");
            statement.executeUpdate("DELETE FROM sharing_consent");
            statement.executeUpdate("DELETE FROM child_guardian");
            statement.executeUpdate("DELETE FROM invitation");
            statement.executeUpdate("DELETE FROM child_organization");
            statement.executeUpdate("DELETE FROM child");
            statement.executeUpdate("DELETE FROM users");
            statement.executeUpdate("DELETE FROM organization");
        }
    }

    private String lockChild() {
        return "SELECT id FROM child WHERE id = " + child.getId() + " FOR UPDATE";
    }

    @Test
    void 제외가_먼저면_추가_발급은_404이고_쓸_수_있는_코드가_남지_않는다() throws Exception {
        List<String> outcomes = inOrderWhileHeld(lockChild(),
                () -> { childService.removeFromRoster(STAFF, child.getId()); return null; },
                () -> invitationService.issueFor(STAFF, child.getId()));

        assertThat(outcomes).containsExactly("OK", "CHILD_NOT_FOUND");
        assertThat(invitationRepository.findAll()).isEmpty();
        assertThat(childOrganizationRepository.findById(linkId).orElseThrow().isDeleted()).isTrue();
    }

    /** 발급이 명부를 잠그지 않으면 이 순서에서 "확인 → 제외·취소 → 저장" 이 되어 쓸 수 있는 코드가 남는다. */
    @Test
    void 발급이_먼저면_제외가_그_코드까지_취소한다() throws Exception {
        List<String> outcomes = inOrderWhileHeld(lockChild(),
                () -> invitationService.issueFor(STAFF, child.getId()),
                () -> { childService.removeFromRoster(STAFF, child.getId()); return null; });

        assertThat(outcomes).containsExactly("OK", "OK");
        assertThat(invitationRepository.findAll()).extracting(Invitation::getStatus)
                .containsExactly(InvitationStatus.CANCELLED);
    }

    /** 발급이 아이 행을 잠그기 전에 확인만 하고 지나가지 않는지 — 명부 행을 잡은 홀더 뒤에서도 기다려야 한다. */
    @Test
    void 명부_행이_잠겨_있으면_발급은_기다렸다가_커밋된_제외를_본다() throws Exception {
        List<String> outcomes = inOrderWhileHeld(
                "UPDATE child_organization SET deleted_at = now(), external_id = NULL WHERE id = " + linkId,
                () -> invitationService.issueFor(STAFF, child.getId()));

        assertThat(outcomes).containsExactly("CHILD_NOT_FOUND");
        assertThat(invitationRepository.findAll()).isEmpty();
    }

    @Test
    void 제외가_먼저면_수락은_400이고_보호자가_연결되지_않는다() throws Exception {
        String code = invitationService.issue(organization.getId(), child.getId()).code();

        List<String> outcomes = inOrderWhileHeld(lockChild(),
                () -> { childService.removeFromRoster(STAFF, child.getId()); return null; },
                () -> invitationService.accept(MOTHER, code));

        assertThat(outcomes).containsExactly("OK", "INVALID_INVITE_CODE");
        assertThat(invitationRepository.findAll()).extracting(Invitation::getStatus)
                .containsExactly(InvitationStatus.CANCELLED);
        assertThat(childGuardianRepository.findAll()).isEmpty();
    }

    @Test
    void 수락이_먼저면_연결된_뒤_제외되고_보호자_연결은_남는다() throws Exception {
        String code = invitationService.issue(organization.getId(), child.getId()).code();

        List<String> outcomes = inOrderWhileHeld(lockChild(),
                () -> invitationService.accept(MOTHER, code),
                () -> { childService.removeFromRoster(STAFF, child.getId()); return null; });

        assertThat(outcomes).containsExactly("OK", "OK");
        assertThat(invitationRepository.findAll()).extracting(Invitation::getStatus)
                .containsExactly(InvitationStatus.ACCEPTED);
        assertThat(childGuardianRepository.findAll()).singleElement()
                .satisfies(guardian -> assertThat(guardian.isDeleted()).isFalse());
        assertThat(childOrganizationRepository.findById(linkId).orElseThrow().isDeleted()).isTrue();
    }

    /**
     * 홀더가 {@code holderSql} 로 행을 잠근 채 있는 동안 작업을 <b>하나씩</b> 보내, 앞 작업이 줄을 선 것을 확인한 뒤
     * 다음 작업을 보낸다. 모두 줄을 서면 홀더를 끝낸다 — SELECT ... FOR UPDATE 면 롤백(잠금만 풀기), UPDATE 면
     * 커밋(변경을 확정)한다. 각 작업의 결과를 "OK" 또는 오류 코드 이름으로 돌려준다.
     */
    @SafeVarargs
    private List<String> inOrderWhileHeld(String holderSql, Callable<Object>... tasks) throws Exception {
        boolean commitHolder = holderSql.startsWith("UPDATE");
        ExecutorService executor = Executors.newFixedThreadPool(tasks.length);
        List<Future<Object>> futures = new ArrayList<>();
        boolean holderFinished = false;

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                int holderPid = backendPid(holder);
                try (Statement statement = holder.createStatement()) {
                    statement.execute(holderSql);
                }
                for (int i = 0; i < tasks.length; i++) {
                    futures.add(executor.submit(tasks[i]));
                    waitUntilBlockedBy(holderPid, i + 1);
                }
                if (commitHolder) {
                    holder.commit();
                } else {
                    holder.rollback();
                }
                holderFinished = true;

                List<String> outcomes = new ArrayList<>();
                for (Future<Object> future : futures) {
                    outcomes.add(outcomeOf(future));
                }
                return outcomes;
            } finally {
                // 실패해도 이 순서로 정리한다: 잠금을 풀어 작업 스레드를 빼내고 → 스레드를 끝내고 → 커넥션을 닫는다.
                if (!holderFinished) {
                    holder.rollback();
                }
                futures.forEach(future -> future.cancel(true));
                executor.shutdownNow();
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    fail("작업 스레드가 끝나지 않았습니다.");
                }
            }
        }
    }

    private static String outcomeOf(Future<Object> future) throws Exception {
        try {
            future.get(10, TimeUnit.SECONDS);
            return "OK";
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ChildException childException) {
                return childException.getErrorCode().name();
            }
            if (e.getCause() instanceof InvitationException invitationException) {
                return invitationException.getErrorCode().name();
            }
            throw e;
        }
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_backend_pid()");
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    /**
     * 홀더 때문에 막힌 세션이 생기고, 잠금을 기다리는 세션이 모두 {@code expected} 개가 될 때까지 기다린다.
     * 같은 행을 둘이 기다리면 두 번째는 첫 번째 대기자 뒤에 줄을 서서, 막은 쪽이 홀더가 아니라 첫 번째 대기자로 보인다.
     */
    private void waitUntilBlockedBy(int holderPid, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (Connection observer = dataSource.getConnection();
             PreparedStatement statement = observer.prepareStatement(
                     "SELECT count(*) FILTER (WHERE ? = ANY(pg_blocking_pids(pid))), "
                             + "count(*) FILTER (WHERE cardinality(pg_blocking_pids(pid)) > 0) "
                             + "FROM pg_stat_activity")) {
            statement.setInt(1, holderPid);
            while (System.nanoTime() < deadline) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    if (resultSet.getInt(1) > 0 && resultSet.getInt(2) >= expected) {
                        return;
                    }
                }
                Thread.sleep(50);
            }
        }
        fail("작업 세션이 홀더 때문에 멈추지 않았습니다. 잠금을 거치지 않고 끝났을 수 있습니다.");
    }
}
