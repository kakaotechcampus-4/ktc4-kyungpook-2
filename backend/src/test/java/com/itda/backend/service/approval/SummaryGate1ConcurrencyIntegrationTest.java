package com.itda.backend.service.approval;

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
import java.util.List;
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

import com.itda.backend.domain.approval.ApprovalDecision;
import com.itda.backend.domain.approval.Gate1Action;
import com.itda.backend.domain.approval.ApprovalTargetType;
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.record.RawRecordStatus;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.domain.summary.SummaryStatus;
import com.itda.backend.exception.summary.SummaryException;
import com.itda.backend.exception.summary.SummaryErrorCode;
import com.itda.backend.repository.approval.ApprovalRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.consent.SharingConsentRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.record.RawRecordRepository;
import com.itda.backend.repository.summary.ChildContextRepository;
import com.itda.backend.repository.summary.SummaryResultRepository;
import com.itda.backend.service.summary.ClaimedSummaryGroup;
import com.itda.backend.service.summary.SummaryGroup;
import com.itda.backend.service.summary.SummaryRunRequests;
import com.itda.backend.service.summary.SummaryService;
import com.itda.backend.service.user.UserService;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class SummaryGate1ConcurrencyIntegrationTest {

    private static final Long ORG = 3L;
    private static final Long CHILD = 8L;
    private static final String TEACHER = "99";
    private static final LocalDate DATE = LocalDate.of(2026, 10, 8);

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("itda_gate1_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired private SummaryGate1Service service;
    @Autowired private SummaryResultRepository summaryResultRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private ChildContextRepository childContextRepository;
    @Autowired private ApprovalRepository approvalRepository;
    @Autowired private RawRecordRepository rawRecordRepository;
    @Autowired private SharingConsentRepository sharingConsentRepository;
    @Autowired private ChildRepository childRepository;
    @Autowired private SummaryService summaryService;
    @Autowired private SummaryRunRequests runRequests;
    @Autowired private DataSource dataSource;

    @MockitoBean private UserService userService;

    private Long rawRecordId;
    private SummaryResult summary;
    private JournalEntry entry;

    @BeforeEach
    void setUp() {
        given(userService.getOrganizationIdOf(TEACHER)).willReturn(ORG);
        sharingConsentRepository.save(SharingConsent.grant(CHILD, 100L, ORG, List.of(ConsentField.DAILY_SUMMARY)));
        rawRecordId = rawRecordRepository.save(new RawRecord(String.valueOf(ORG), "school.pdf", "kept/school.pdf",
                "application/pdf", 12, RawRecordStatus.PENDING)).getId();
        summary = summaryResultRepository.save(SummaryResult.of(CHILD, DATE, ORG, 1,
                "블록 놀이에서 친구에게 양보했다.", "[]", "[]", "[]", "{}"));
        JournalEntry pending = validated();
        pending.startSummarizing();
        pending.completeSummary(summary.getId());
        entry = journalEntryRepository.save(pending);
    }

    @AfterEach
    void cleanUp() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM human_review");
            statement.executeUpdate("DELETE FROM child_context");
            statement.executeUpdate("DELETE FROM journal_entry");
            statement.executeUpdate("DELETE FROM summary_result");
            statement.executeUpdate("DELETE FROM summary_run_request");
            statement.executeUpdate("DELETE FROM raw_record");
            statement.executeUpdate("DELETE FROM sharing_consent");
            statement.executeUpdate("DELETE FROM child");
        }
    }

    private JournalEntry validated() {
        JournalEntry e = JournalEntry.of(rawRecordId, DATE, "블록 놀이를 했다.", 1);
        e.startMatching();
        e.confirmMatch(CHILD);
        e.startValidating();
        e.passValidation();
        return e;
    }

    @Test
    void 승인이_먼저_잡고_있으면_워커는_기다렸다가_승인된_일지를_다시_묶지_않는다() throws Exception {
        JournalEntry late = journalEntryRepository.save(validated());
        runRequests.request(new SummaryGroup(CHILD, DATE, ORG), LocalDateTime.now());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        List<ClaimedSummaryGroup> claimed;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            int holderPid = backendPid(holder);
            try (Statement statement = holder.createStatement()) {
                statement.executeQuery("SELECT id FROM summary_result WHERE id = " + summary.getId() + " FOR UPDATE");
                statement.executeUpdate("UPDATE summary_result SET status = 'APPROVED' WHERE id = " + summary.getId());
                statement.executeUpdate("UPDATE journal_entry SET status = 'COMPLETED' WHERE id = " + entry.getId());
            }

            Future<List<ClaimedSummaryGroup>> claim = executor.submit(() -> summaryService.claimReadyGroups(10));
            waitUntilBlockedBy(holderPid);
            holder.commit();
            claimed = claim.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(claimed).singleElement().satisfies(group -> {
            assertThat(group.group()).isEqualTo(new SummaryGroup(CHILD, DATE, ORG));
            assertThat(group.journalEntryIds()).containsExactly(late.getId());
        });
        assertThat(journalEntryRepository.findById(entry.getId()).orElseThrow().getStatus())
                .isEqualTo(JournalEntryStatus.COMPLETED);
    }

    @Test
    void 다른_결정이_먼저_잡고_있으면_기다렸다가_이미_처리된_요약으로_거절한다() throws Exception {
        String version = versionOf(summary.getId());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            int holderPid = backendPid(holder);
            try (Statement statement = holder.createStatement()) {
                statement.executeUpdate(
                        "UPDATE summary_result SET status = 'APPROVED' WHERE id = " + summary.getId());
            }

            Future<?> approve = executor.submit(
                    () -> service.decide(TEACHER, summary.getId(), Gate1Action.APPROVE, null, version));
            waitUntilBlockedBy(holderPid);
            holder.commit();

            try {
                approve.get(10, TimeUnit.SECONDS);
                fail("이미 승인된 요약을 다시 승인했습니다. 잠그지 않고 커밋 전 상태를 읽었을 수 있습니다.");
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(SummaryException.class);
                assertThat(((SummaryException) e.getCause()).getErrorCode())
                        .isEqualTo(SummaryErrorCode.ALREADY_REVIEWED);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(summaryResultRepository.findById(summary.getId()).orElseThrow().getStatus())
                .isEqualTo(SummaryStatus.APPROVED);
        assertThat(childContextRepository.findByChildIdOrderByIdDesc(CHILD)).isEmpty();
        assertThat(approvalRepository.findByTargetTypeAndTargetId(ApprovalTargetType.SUMMARY, summary.getId()))
                .isEmpty();
    }

    @Test
    void 동의_철회가_먼저_잡고_있으면_기다렸다가_철회된_아이로_거절한다() throws Exception {
        Long childId = childRepository.save(Child.of("김준호", LocalDate.of(2020, 3, 1))).getId();
        sharingConsentRepository.save(SharingConsent.grant(childId, 100L, ORG, List.of(ConsentField.DAILY_SUMMARY)));
        SummaryResult target = summaryResultRepository.save(SummaryResult.of(childId, DATE, ORG, 1,
                "블록 놀이에서 친구에게 양보했다.", "[]", "[]", "[]", "{}"));
        JournalEntry pending = JournalEntry.of(rawRecordId, DATE, "블록 놀이를 했다.", 1);
        pending.startMatching();
        pending.confirmMatch(childId);
        pending.startValidating();
        pending.passValidation();
        pending.startSummarizing();
        pending.completeSummary(target.getId());
        journalEntryRepository.save(pending);
        String version = versionOf(target.getId());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            int holderPid = backendPid(holder);
            try (Statement statement = holder.createStatement()) {
                statement.executeQuery("SELECT id FROM child WHERE id = " + childId + " FOR UPDATE");
                statement.executeUpdate("UPDATE sharing_consent SET status = 'REVOKED', revoked_at = now(), "
                        + "revoke_reason = 'GUARDIAN_REVOKED' WHERE child_id = " + childId);
            }

            Future<?> approve = executor.submit(
                    () -> service.decide(TEACHER, target.getId(), Gate1Action.APPROVE, null, version));
            waitUntilBlockedBy(holderPid);
            holder.commit();

            try {
                approve.get(10, TimeUnit.SECONDS);
                fail("철회된 동의로 승인했습니다. 아이 행을 잠그지 않고 커밋 전 동의를 읽었을 수 있습니다.");
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(SummaryException.class);
                assertThat(((SummaryException) e.getCause()).getErrorCode()).isEqualTo(SummaryErrorCode.NOT_FOUND);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(summaryResultRepository.findById(target.getId()).orElseThrow().getStatus())
                .isEqualTo(SummaryStatus.GATE1_PENDING);
        assertThat(childContextRepository.findByChildIdOrderByIdDesc(childId)).isEmpty();
    }

    @Test
    void 다른_기관의_요청은_요약을_잠그지_않고_바로_없음으로_응답한다() throws Exception {
        given(userService.getOrganizationIdOf("other")).willReturn(ORG + 1);
        String version = versionOf(summary.getId());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement statement = holder.createStatement()) {
                statement.executeQuery("SELECT id FROM summary_result WHERE id = " + summary.getId() + " FOR UPDATE");
            }

            Future<?> foreign = executor.submit(
                    () -> service.decide("other", summary.getId(), Gate1Action.APPROVE, null, version));
            try {
                foreign.get(3, TimeUnit.SECONDS);
                fail("다른 기관 요약을 결정했습니다.");
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(SummaryException.class);
                assertThat(((SummaryException) e.getCause()).getErrorCode()).isEqualTo(SummaryErrorCode.NOT_FOUND);
            } catch (java.util.concurrent.TimeoutException e) {
                fail("다른 기관 요청이 요약 행 잠금을 기다렸습니다.");
            }
            holder.rollback();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 새로_더한_상태와_결정값을_저장한다() {
        service.decide(TEACHER, summary.getId(), Gate1Action.HOLD, null, versionOf(summary.getId()));
        assertThat(journalEntryRepository.findById(entry.getId()).orElseThrow().getStatus())
                .isEqualTo(JournalEntryStatus.GATE1_HELD);

        service.reopen(TEACHER, summary.getId());
        service.decide(TEACHER, summary.getId(), Gate1Action.REMOVE, null, versionOf(summary.getId()));

        assertThat(summaryResultRepository.findById(summary.getId()).orElseThrow().getStatus())
                .isEqualTo(SummaryStatus.REMOVED);
        assertThat(journalEntryRepository.findById(entry.getId()).orElseThrow().isDeleted()).isTrue();
        assertThat(approvalRepository.findByTargetTypeAndTargetId(ApprovalTargetType.SUMMARY, summary.getId()))
                .extracting(a -> a.getDecision())
                .containsExactlyInAnyOrder(ApprovalDecision.HOLD, ApprovalDecision.REOPENED, ApprovalDecision.REMOVED);
    }

    private String versionOf(Long summaryId) {
        return Gate1Version.of(summaryResultRepository.findById(summaryId).orElseThrow(),
                journalEntryRepository.findBySummaryIdAndDeletedAtIsNull(summaryId));
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_backend_pid()");
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private void waitUntilBlockedBy(int holderPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (Connection observer = dataSource.getConnection();
             PreparedStatement statement = observer.prepareStatement(
                     "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))")) {
            statement.setInt(1, holderPid);
            while (System.nanoTime() < deadline) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    if (resultSet.getInt(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(50);
            }
        }
        fail("승인 요청이 홀더 때문에 멈추지 않았습니다.");
    }
}
