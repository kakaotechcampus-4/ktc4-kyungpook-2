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
import com.itda.backend.domain.approval.ApprovalTargetType;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.domain.summary.SummaryStatus;
import com.itda.backend.exception.summary.SummaryConflictException;
import com.itda.backend.exception.summary.SummaryErrorCode;
import com.itda.backend.repository.approval.ApprovalRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.summary.ChildContextRepository;
import com.itda.backend.repository.summary.SummaryResultRepository;
import com.itda.backend.service.user.UserService;

/**
 * Gate 1 결정이 다른 결정과 겹칠 때와 새 enum 값 저장을 PostgreSQL 에서 확인한다.
 *
 * <p>다른 커넥션(홀더)이 요약을 보류로 바꾼 채 커밋하지 않고 잡아 둔다. 승인 요청이 홀더 때문에 멈췄는지
 * {@code pg_blocking_pids} 로 확인한 뒤 홀더를 커밋해, 승인이 잠금을 기다린 다음 최신 상태(보류)로 판단해
 * 409 를 내는지 본다. 잠그지 않고 읽으면 커밋 전 값(대기)을 보고 보류를 승인으로 덮어쓴다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class SummaryGate1ConcurrencyIntegrationTest {

    private static final Long ORG = 3L;
    private static final Long CHILD = 8L;
    private static final String TEACHER = "99";

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
    @Autowired private DataSource dataSource;

    @MockitoBean private UserService userService;

    private SummaryResult summary;
    private JournalEntry entry;

    @BeforeEach
    void setUp() {
        given(userService.getOrganizationIdOf(TEACHER)).willReturn(ORG);
        summary = summaryResultRepository.save(SummaryResult.of(CHILD, LocalDate.of(2026, 10, 8), ORG, 1,
                "블록 놀이에서 친구에게 양보했다.", "[]", "[]", "[]", "{}"));
        JournalEntry pending = JournalEntry.of(1L, LocalDate.of(2026, 10, 8), "블록 놀이를 했다.", 1);
        pending.startMatching();
        pending.confirmMatch(CHILD);
        pending.startValidating();
        pending.passValidation();
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
        }
    }

    @Test
    void 다른_결정이_먼저_잡고_있으면_기다렸다가_이미_처리된_요약으로_거절한다() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            int holderPid = backendPid(holder);
            try (Statement statement = holder.createStatement()) {
                statement.executeUpdate("UPDATE summary_result SET status = 'HOLD' WHERE id = " + summary.getId());
            }

            Future<?> approve = executor.submit(
                    () -> service.decide(TEACHER, summary.getId(), Gate1Action.APPROVE, null));
            waitUntilBlockedBy(holderPid);
            holder.commit();

            try {
                approve.get(10, TimeUnit.SECONDS);
                fail("보류된 요약을 승인했습니다. 잠그지 않고 커밋 전 상태를 읽었을 수 있습니다.");
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(SummaryConflictException.class);
                assertThat(((SummaryConflictException) e.getCause()).getErrorCode())
                        .isEqualTo(SummaryErrorCode.ALREADY_REVIEWED);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(summaryResultRepository.findById(summary.getId()).orElseThrow().getStatus())
                .isEqualTo(SummaryStatus.HOLD);
        assertThat(childContextRepository.findByChildIdOrderByIdDesc(CHILD)).isEmpty();
        assertThat(approvalRepository.findByTargetTypeAndTargetId(ApprovalTargetType.SUMMARY, summary.getId()))
                .isEmpty();
    }

    @Test
    void 새로_더한_상태와_결정값을_저장한다() {
        // 새 테이블에서는 Hibernate 가 새 enum 값으로 CHECK 제약을 만든다. 배포 DB 는 §11.4 수동 조치가 필요하다.
        service.decide(TEACHER, summary.getId(), Gate1Action.HOLD, null);
        assertThat(journalEntryRepository.findById(entry.getId()).orElseThrow().getStatus())
                .isEqualTo(JournalEntryStatus.GATE1_HELD);

        service.reopen(TEACHER, summary.getId());
        service.decide(TEACHER, summary.getId(), Gate1Action.REMOVE, null);

        assertThat(summaryResultRepository.findById(summary.getId()).orElseThrow().getStatus())
                .isEqualTo(SummaryStatus.REMOVED);
        assertThat(journalEntryRepository.findById(entry.getId()).orElseThrow().isDeleted()).isTrue();
        assertThat(approvalRepository.findByTargetTypeAndTargetId(ApprovalTargetType.SUMMARY, summary.getId()))
                .extracting(a -> a.getDecision())
                .containsExactlyInAnyOrder(ApprovalDecision.HOLD, ApprovalDecision.REOPENED, ApprovalDecision.REMOVED);
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
