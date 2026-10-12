package com.itda.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.BDDMockito.given;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
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

import com.itda.backend.domain.Child;
import com.itda.backend.domain.ChildGuardian;
import com.itda.backend.domain.ChildOrganization;
import com.itda.backend.domain.Invitation;
import com.itda.backend.domain.InvitationStatus;
import com.itda.backend.domain.Organization;
import com.itda.backend.domain.User;
import com.itda.backend.exception.InvitationException;
import com.itda.backend.fixture.MutableClock;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.repository.ChildGuardianRepository;
import com.itda.backend.repository.ChildOrganizationRepository;
import com.itda.backend.repository.ChildRepository;
import com.itda.backend.repository.InvitationRepository;
import com.itda.backend.repository.OrganizationRepository;
import com.itda.backend.repository.UserRepository;

/**
 * 초대 코드 수락이 동시에 진행되는 다른 작업과 겹칠 때를 PostgreSQL 에서 재현한다.
 *
 * <p>다른 커넥션(홀더)이 행을 잠그거나 바꾼 채 커밋하지 않고 잡아 둔다. 수락 요청이 "홀더 때문에" 멈췄는지
 * {@code pg_blocking_pids} 로 확인한 뒤 홀더를 풀어, 수락이 잠금을 기다린 다음 최신 상태로 판단하는지 본다.
 * 시각은 테스트가 옮길 수 있는 시계로 정한다 — 잠금을 기다리는 동안 만료되는 경우를 만들기 위해서다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
@Import(InvitationAcceptConcurrencyIntegrationTest.ClockConfig.class)
class InvitationAcceptConcurrencyIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 10, 0);
    private static final String MOTHER = "u-mother";
    private static final String FATHER = "u-father";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("itda_invitation_accept_test").withUsername("test").withPassword("test");

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

    @Autowired private InvitationService invitationService;
    @Autowired private ChildGuardianService childGuardianService;
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
    private User mother;
    private User father;

    @BeforeEach
    void setUp() {
        clock.setTo(NOW);
        organization = organizationRepository.save(OrganizationFixture.center());
        child = childRepository.save(Child.of("김하늘", LocalDate.of(2017, 3, 14)));
        childOrganizationRepository.save(ChildOrganization.of(child.getId(), organization.getId()));
        mother = userRepository.save(UserFixture.parent("kakao-mother", "김엄마"));
        father = userRepository.save(UserFixture.parent("kakao-father", "김아빠"));
        given(userService.getParentIdOf(MOTHER)).willReturn(mother.getId());
        given(userService.getParentIdOf(FATHER)).willReturn(father.getId());
    }

    @AfterEach
    void cleanUp() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM child_guardian");
            statement.executeUpdate("DELETE FROM invitation");
            statement.executeUpdate("DELETE FROM child_organization");
            statement.executeUpdate("DELETE FROM child");
            statement.executeUpdate("DELETE FROM users");
            statement.executeUpdate("DELETE FROM organization");
        }
    }

    private String issue() {
        return invitationService.issue(organization.getId(), child.getId()).code();
    }

    /** 첫 코드로 연결했다가 본인이 끊어, 삭제된 연결 행을 남긴다. 재연결(복구) 경로를 타게 하려고 쓴다. */
    private void leaveDeletedLink(String userId) {
        invitationService.accept(userId, issue());
        childGuardianService.unlinkBySelf(userId, child.getId());
    }

    @Test
    void 같은_코드를_두_보호자가_동시에_쓰면_한_명만_연결된다() throws Exception {
        String code = issue();

        List<String> outcomes = whileHeld("SELECT id FROM child WHERE id = " + child.getId() + " FOR UPDATE", 2, null,
                () -> invitationService.accept(MOTHER, code),
                () -> invitationService.accept(FATHER, code));

        assertThat(outcomes).containsExactlyInAnyOrder("OK", "INVALID_INVITE_CODE");
        assertThat(activeGuardians()).hasSize(1);
    }

    @Test
    void 같은_보호자가_다른_코드_두_개로_동시에_새로_연결해도_하나만_쓰인다() throws Exception {
        String first = issue();
        String second = issue();

        List<String> outcomes = whileHeld("SELECT id FROM child WHERE id = " + child.getId() + " FOR UPDATE", 2, null,
                () -> invitationService.accept(MOTHER, first),
                () -> invitationService.accept(MOTHER, second));

        assertThat(outcomes).containsExactlyInAnyOrder("OK", "LINK_ALREADY_EXISTS");
        assertThat(statusesOf(first, second))
                .containsExactlyInAnyOrder(InvitationStatus.ACCEPTED, InvitationStatus.PENDING);
        assertThat(childGuardianRepository.findAll()).hasSize(1);
    }

    /** 복구는 UPDATE 라 유니크 제약이 막아 주지 않는다. 잠근 뒤 다시 확인하지 않으면 둘 다 성공한다. */
    @Test
    void 같은_보호자가_다른_코드_두_개로_동시에_재연결해도_하나만_쓰인다() throws Exception {
        leaveDeletedLink(MOTHER);
        String first = issue();
        String second = issue();

        List<String> outcomes = whileHeld("SELECT id FROM child WHERE id = " + child.getId() + " FOR UPDATE", 2, null,
                () -> invitationService.accept(MOTHER, first),
                () -> invitationService.accept(MOTHER, second));

        assertThat(outcomes).containsExactlyInAnyOrder("OK", "LINK_ALREADY_EXISTS");
        assertThat(statusesOf(first, second))
                .containsExactlyInAnyOrder(InvitationStatus.ACCEPTED, InvitationStatus.PENDING);
        assertThat(childGuardianRepository.findAll()).singleElement()
                .satisfies(guardian -> assertThat(guardian.isDeleted()).isFalse());
    }

    @Test
    void 초대_행_잠금을_기다리는_동안_만료되면_400이다() throws Exception {
        String code = issue();
        Long invitationId = invitationRepository.findAll().get(0).getId();

        List<String> outcomes = whileHeld("SELECT id FROM invitation WHERE id = " + invitationId + " FOR UPDATE", 1,
                () -> clock.advance(Duration.ofMinutes(11)),
                () -> invitationService.accept(MOTHER, code));

        assertThat(outcomes).containsExactly("INVALID_INVITE_CODE");
        assertThat(statusesOf(code)).containsExactly(InvitationStatus.PENDING);
        assertThat(childGuardianRepository.findAll()).isEmpty();
    }

    /** 초대 행 잠금 뒤의 만료 확인은 통과했지만, 연결 행 잠금을 기다리는 사이 만료됐다. 복구까지 함께 롤백돼야 한다. */
    @Test
    void 연결_행_잠금을_기다리는_동안_만료되면_400이고_복구도_롤백된다() throws Exception {
        leaveDeletedLink(MOTHER);
        Long previousInvitationId = childGuardianRepository.findAll().get(0).getInvitationId();
        String code = issue();

        List<String> outcomes = whileHeld(
                "SELECT id FROM child_guardian WHERE child_id = " + child.getId()
                        + " AND user_id = " + mother.getId() + " FOR UPDATE", 1,
                () -> clock.advance(Duration.ofMinutes(11)),
                () -> invitationService.accept(MOTHER, code));

        assertThat(outcomes).containsExactly("INVALID_INVITE_CODE");
        assertThat(statusesOf(code)).containsExactly(InvitationStatus.PENDING);
        assertThat(childGuardianRepository.findAll()).singleElement().satisfies(guardian -> {
            assertThat(guardian.isDeleted()).isTrue();
            assertThat(guardian.getInvitationId()).isEqualTo(previousInvitationId);
        });
    }

    @Test
    void 명부_제외와_수락이_겹치면_제외가_이긴다() throws Exception {
        String code = issue();

        List<String> outcomes = whileHeld(
                "UPDATE child_organization SET deleted_at = now() WHERE child_id = " + child.getId()
                        + " AND organization_id = " + organization.getId(), 1,
                null,
                () -> invitationService.accept(MOTHER, code));

        assertThat(outcomes).containsExactly("INVALID_INVITE_CODE");
        assertThat(statusesOf(code)).containsExactly(InvitationStatus.PENDING);
        assertThat(childGuardianRepository.findAll()).isEmpty();
    }

    @Test
    void 아동_삭제와_수락이_겹치면_삭제가_이긴다() throws Exception {
        String code = issue();

        List<String> outcomes = whileHeld(
                "UPDATE child SET deleted_at = now() WHERE id = " + child.getId(), 1,
                null,
                () -> invitationService.accept(MOTHER, code));

        assertThat(outcomes).containsExactly("INVALID_INVITE_CODE");
        assertThat(statusesOf(code)).containsExactly(InvitationStatus.PENDING);
        assertThat(childGuardianRepository.findAll()).isEmpty();
    }

    /**
     * 홀더가 {@code holderSql} 을 실행한 채 커밋하지 않고 있는 동안 수락들을 보내고, {@code expectedBlocked} 개의 세션이
     * 홀더 때문에 멈출 때까지 기다린다. 그다음 {@code whileBlocked} 를 실행하고 홀더를 끝낸다 — SELECT ... FOR UPDATE 면
     * 롤백(잠금만 풀기), UPDATE 면 커밋(변경을 확정)한다. 각 수락의 결과를 "OK" 또는 오류 코드 이름으로 돌려준다.
     */
    private List<String> whileHeld(String holderSql, int expectedBlocked, Runnable whileBlocked,
            Callable<?>... accepts) throws Exception {
        boolean commitHolder = holderSql.startsWith("UPDATE");
        ExecutorService executor = Executors.newFixedThreadPool(accepts.length);
        List<Future<?>> futures = new ArrayList<>();
        boolean holderFinished = false;

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                int holderPid = backendPid(holder);
                try (Statement statement = holder.createStatement()) {
                    statement.execute(holderSql);
                }
                for (Callable<?> accept : accepts) {
                    futures.add(executor.submit(accept));
                }
                waitUntilBlockedBy(holderPid, expectedBlocked);
                if (whileBlocked != null) {
                    whileBlocked.run();
                }
                if (commitHolder) {
                    holder.commit();
                } else {
                    holder.rollback();
                }
                holderFinished = true;

                List<String> outcomes = new ArrayList<>();
                for (Future<?> future : futures) {
                    outcomes.add(outcomeOf(future));
                }
                return outcomes;
            } finally {
                // 실패해도 이 순서로 정리한다: 잠금을 풀어 수락 스레드를 빼내고 → 스레드를 끝내고 → 커넥션을 닫는다.
                if (!holderFinished) {
                    holder.rollback();
                }
                futures.forEach(future -> future.cancel(true));
                executor.shutdownNow();
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    fail("수락 스레드가 끝나지 않았습니다.");
                }
            }
        }
    }

    private static String outcomeOf(Future<?> future) throws Exception {
        try {
            future.get(10, TimeUnit.SECONDS);
            return "OK";
        } catch (ExecutionException e) {
            if (e.getCause() instanceof InvitationException invitationException) {
                return invitationException.getErrorCode().name();
            }
            throw e;
        }
    }

    private List<ChildGuardian> activeGuardians() {
        return childGuardianRepository.findAll().stream().filter(guardian -> !guardian.isDeleted()).toList();
    }

    private List<InvitationStatus> statusesOf(String... codes) {
        List<String> wanted = List.of(codes);
        return invitationRepository.findAll().stream()
                .filter(invitation -> wanted.contains(invitation.getCode()))
                .map(Invitation::getStatus)
                .toList();
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
        fail("수락 세션이 홀더 때문에 멈추지 않았습니다. 잠금을 거치지 않고 끝났을 수 있습니다.");
    }
}
