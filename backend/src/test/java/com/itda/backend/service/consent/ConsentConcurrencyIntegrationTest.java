package com.itda.backend.service.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.child.ChildStatus;
import com.itda.backend.domain.consent.ConsentAction;
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.ConsentRevokeReason;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.matching.MatchingResult;
import com.itda.backend.domain.matching.MatchingStatus;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.record.RawRecordStatus;
import com.itda.backend.domain.user.User;
import com.itda.backend.exception.consent.ConsentException;
import com.itda.backend.exception.matching.MatchingResultValidationException;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.consent.SharingConsentRepository;
import com.itda.backend.repository.matching.MatchingResultRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.record.RawRecordRepository;
import com.itda.backend.repository.user.UserRepository;
import com.itda.backend.service.invitation.ChildGuardianService;
import com.itda.backend.service.invitation.InvitationService;
import com.itda.backend.service.matching.MatchingResultService;
import com.itda.backend.service.user.UserService;

/**
 * 동의 변경이 다른 작업과 겹칠 때를 PostgreSQL 에서 재현한다. 동의 부여·철회, 연결 해제, 사람의 매칭 확정은 모두
 * {@code child} 행을 먼저 잠근다 — 이 잠금이 그 작업들을 한 줄로 세우는지 본다.
 *
 * <p>다른 커넥션(홀더)이 {@code child} 행을 잠근 채 잡아 두고, 작업을 하나씩 보내 홀더 뒤에 줄을 세운다.
 * 줄 선 순서를 {@code pg_blocking_pids} 로 확인한 뒤 홀더를 풀면, 먼저 줄 선 작업이 먼저 잠금을 얻는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class ConsentConcurrencyIntegrationTest {

    private static final String MOTHER = "u-mother";
    private static final String STAFF = "u-staff";
    private static final List<ConsentField> DAILY = List.of(ConsentField.DAILY_SUMMARY);
    private static final String MATCHING_REJECTED = "MATCHING_REJECTED";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("itda_consent_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired private ConsentService consentService;
    @Autowired private ChildGuardianService childGuardianService;
    @Autowired private InvitationService invitationService;
    @Autowired private MatchingResultService matchingResultService;
    @Autowired private ChildRepository childRepository;
    @Autowired private ChildOrganizationRepository childOrganizationRepository;
    @Autowired private SharingConsentRepository sharingConsentRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RawRecordRepository rawRecordRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private MatchingResultRepository matchingResultRepository;
    @Autowired private DataSource dataSource;

    @MockitoBean private UserService userService;

    private Organization organization;
    private Child child;
    private User mother;

    @BeforeEach
    void setUp() {
        organization = organizationRepository.save(OrganizationFixture.center());
        child = childRepository.save(Child.of("김하늘", LocalDate.of(2017, 3, 14)));
        childOrganizationRepository.save(ChildOrganization.of(child.getId(), organization.getId()));
        mother = userRepository.save(UserFixture.parent("kakao-mother", "김엄마"));
        given(userService.getParentIdOf(MOTHER)).willReturn(mother.getId());
        given(userService.getOrganizationIdOf(STAFF)).willReturn(organization.getId());

        invitationService.accept(MOTHER, invitationService.issue(organization.getId(), child.getId()).code());
    }

    @AfterEach
    void cleanUp() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String table : List.of("sharing_consent", "matching_result", "journal_entry", "raw_record",
                    "child_guardian", "invitation", "child_organization", "child", "users", "organization")) {
                statement.executeUpdate("DELETE FROM " + table);
            }
        }
    }

    private Callable<Object> grant() {
        return () -> consentService.update(MOTHER, child.getId(), organization.getId(), ConsentAction.GRANT, DAILY);
    }

    private Callable<Object> revoke() {
        return () -> consentService.update(MOTHER, child.getId(), organization.getId(), ConsentAction.REVOKE, null);
    }

    private Callable<Object> assign(Long matchingResultId) {
        return () -> matchingResultService.resolve(matchingResultId, "assign", child.getId(), "teacher-1", STAFF);
    }

    /** 확인 필요 큐에 올라온 우리 기관 일지 한 건. */
    private MatchingResult reviewItem() {
        RawRecord rawRecord = rawRecordRepository.save(new RawRecord(String.valueOf(organization.getId()),
                "관찰일지.txt", "stored.txt", "text/plain", 10L, RawRecordStatus.PENDING));
        JournalEntry entry = JournalEntry.of(rawRecord.getId(), LocalDate.of(2026, 10, 1), "블록을 높이 쌓음", 1);
        entry.startMatching();
        entry.requestMatchReview();
        entry = journalEntryRepository.save(entry);
        return matchingResultRepository.save(new MatchingResult(entry.getId(), null, new BigDecimal("0.4"),
                MatchingStatus.REVIEW, null, null, null, null, null, null, "v1"));
    }

    private String lockChild() {
        return "SELECT id FROM child WHERE id = " + child.getId() + " FOR UPDATE";
    }

    private List<SharingConsent> activeConsents() {
        return sharingConsentRepository.findAll().stream().filter(SharingConsent::isActive).toList();
    }

    @Test
    void 같은_보호자가_동시에_두_번_동의해도_살아_있는_동의는_하나다() throws Exception {
        List<String> outcomes = inOrderWhileHeld(lockChild(), grant(), grant());

        assertThat(outcomes).containsExactly("OK", "OK");
        assertThat(activeConsents()).hasSize(1);
        assertThat(childRepository.findById(child.getId()).orElseThrow().getStatus()).isEqualTo(ChildStatus.ACTIVE);
    }

    /** 동의가 먼저면 연결 해제가 그 동의를 끈다. 둘 다 child 부터 잠그므로 교착 없이 끝난다. */
    @Test
    void 동의_뒤에_연결이_끊기면_그_동의는_꺼진다() throws Exception {
        List<String> outcomes = inOrderWhileHeld(lockChild(),
                grant(),
                () -> {
                    childGuardianService.unlinkBySelf(MOTHER, child.getId());
                    return null;
                });

        assertThat(outcomes).containsExactly("OK", "OK");
        assertThat(sharingConsentRepository.findAll()).singleElement().satisfies(consent -> {
            assertThat(consent.isActive()).isFalse();
            assertThat(consent.getRevokeReason()).isEqualTo(ConsentRevokeReason.GUARDIAN_UNLINKED);
        });
        assertThat(childRepository.findById(child.getId()).orElseThrow().getStatus()).isEqualTo(ChildStatus.SUSPENDED);
    }

    @Test
    void 연결이_먼저_끊기면_뒤이은_동의는_403이다() throws Exception {
        List<String> outcomes = inOrderWhileHeld(lockChild(),
                () -> {
                    childGuardianService.unlinkBySelf(MOTHER, child.getId());
                    return null;
                },
                grant());

        assertThat(outcomes).containsExactly("OK", "CHILD_NOT_LINKED");
        assertThat(sharingConsentRepository.findAll()).isEmpty();
    }

    /** 철회가 완료된 뒤에는 새 매칭 확정이 성공하지 않는다. */
    @Test
    void 철회가_먼저_끝나면_뒤이은_매칭_확정은_거절된다() throws Exception {
        grant().call();
        MatchingResult item = reviewItem();

        List<String> outcomes = inOrderWhileHeld(lockChild(), revoke(), assign(item.getId()));

        assertThat(outcomes).containsExactly("OK", MATCHING_REJECTED);
        MatchingResult stored = matchingResultRepository.findById(item.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(MatchingStatus.REVIEW);
        assertThat(stored.getMatchedChildId()).isNull();
        assertThat(journalEntryRepository.findById(stored.getJournalEntryId()).orElseThrow().getStatus())
                .isEqualTo(JournalEntryStatus.MATCH_REVIEW);
    }

    /** 이번 범위에서는 이미 확정된 결과를 취소하지 않는다. 확정이 먼저면 확정은 남고, 그 뒤 동의가 꺼진다. */
    @Test
    void 확정이_먼저_끝나면_확정은_유지되고_그_뒤_동의가_철회된다() throws Exception {
        grant().call();
        MatchingResult item = reviewItem();

        List<String> outcomes = inOrderWhileHeld(lockChild(), assign(item.getId()), revoke());

        assertThat(outcomes).containsExactly("OK", "OK");
        MatchingResult stored = matchingResultRepository.findById(item.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(MatchingStatus.AUTO);
        assertThat(stored.getMatchedChildId()).isEqualTo(child.getId());
        assertThat(activeConsents()).isEmpty();
    }

    /**
     * 홀더가 {@code holderSql} 로 행을 잠근 채 있는 동안 작업을 <b>하나씩</b> 보내, 앞 작업이 줄을 선 것을 확인한 뒤
     * 다음 작업을 보낸다. 모두 줄을 서면 홀더를 롤백해 잠금을 푼다. 각 작업의 결과를 "OK" 또는 실패 이름으로 돌려준다.
     */
    @SafeVarargs
    private List<String> inOrderWhileHeld(String holderSql, Callable<Object>... tasks) throws Exception {
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
                holder.rollback();
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
            if (e.getCause() instanceof ConsentException consentException) {
                return consentException.getErrorCode().name();
            }
            if (e.getCause() instanceof MatchingResultValidationException) {
                return MATCHING_REJECTED;
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
