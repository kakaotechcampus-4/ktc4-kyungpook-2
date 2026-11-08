package com.itda.backend.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.itda.backend.domain.approval.Gate1Action;
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.record.RawRecordStatus;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.domain.summary.SummaryStatus;
import com.itda.backend.exception.agent.AgentQueueFullException;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.repository.consent.SharingConsentRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.record.RawRecordRepository;
import com.itda.backend.repository.summary.SummaryResultRepository;
import com.itda.backend.service.approval.Gate1Version;
import com.itda.backend.service.approval.SummaryGate1Service;
import com.itda.backend.service.record.RawRecordService;
import com.itda.backend.service.storage.RawFileStorage;
import com.itda.backend.service.summary.ClaimedSummaryGroup;
import com.itda.backend.service.summary.SummaryGroup;
import com.itda.backend.service.summary.SummaryRunRequests;
import com.itda.backend.service.summary.SummaryService;
import com.itda.backend.service.user.UserService;

/**
 * 기관별 AI 처리 줄 상한(#424)을 PostgreSQL 에서 동시에 두드려 본다. 미리 보기는 잠그지 않으므로 동시 요청이 함께 통과할 수 있고,
 * 상한을 지키는 것은 기관 행을 잠근 뒤의 다시 세기다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class AgentQueueConcurrencyIntegrationTest {

    private static final String TEACHER = "99";
    private static final Long CHILD = 8L;
    private static final LocalDate DATE = LocalDate.of(2026, 10, 8);

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("itda_agent_queue_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("app.agent-queue.max-awaiting-entries", () -> "1000");
        // 다섯 업로드가 모두 바이트 읽기 자리를 얻어 함께 미리 보기를 지나가게 한다 — 잠금 안의 다시 세기를 시험한다.
        registry.add("app.raw-record.upload.max-concurrent", () -> "5");
    }

    @Autowired private RawRecordService rawRecordService;
    @Autowired private AgentQueueGuard guard;
    @Autowired private SummaryGate1Service gate1Service;
    @Autowired private SummaryService summaryService;
    @Autowired private SummaryRunRequests runRequests;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private RawRecordRepository rawRecordRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private SummaryResultRepository summaryResultRepository;
    @Autowired private SharingConsentRepository sharingConsentRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private DataSource dataSource;

    @MockitoBean private UserService userService;
    @MockitoBean private RawFileStorage rawFileStorage;

    private Long org;
    private Long rawRecordId;

    @BeforeEach
    void setUp() throws Exception {
        org = organizationRepository.save(OrganizationFixture.center()).getId();
        rawRecordId = rawRecordRepository.save(new RawRecord(String.valueOf(org), "old.txt", "kept/old.txt",
                "text/plain", 10, RawRecordStatus.PENDING)).getId();
        given(userService.getOrganizationIdOf(TEACHER)).willReturn(org);
        AtomicInteger keys = new AtomicInteger();
        given(rawFileStorage.store(any(), anyString())).willAnswer(invocation -> "stored-" + keys.incrementAndGet());
    }

    @AfterEach
    void cleanUp() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM human_review");
            statement.executeUpdate("DELETE FROM journal_entry");
            statement.executeUpdate("DELETE FROM summary_result");
            statement.executeUpdate("DELETE FROM summary_run_request");
            statement.executeUpdate("DELETE FROM raw_record");
            statement.executeUpdate("DELETE FROM sharing_consent");
            statement.executeUpdate("DELETE FROM organization");
        }
    }

    @Test
    void 동시에_올린_파일들은_상한을_넘겨_함께_들어가지_못한다() throws Exception {
        insertPending(900);

        List<Object> outcomes = runTogether(5, () -> rawRecordService.ingest(TEACHER, datedTxt(100)));

        assertThat(outcomes).filteredOn(RawRecord.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(AgentQueueFullException.class::isInstance).hasSize(4);
        assertThat(awaiting()).isEqualTo(1000);
        // 원본 행은 처음 것과 받은 것 하나뿐이다.
        assertThat(rawRecordRepository.count()).isEqualTo(2);
        // 저장소에 올린 뒤 잠금 안에서 거절된 파일은 지웠다(미리 보기에서 거절된 파일은 올리지도 않았다).
        int stored = mockingDetails(rawFileStorage).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("store")).mapToInt(i -> 1).sum();
        verify(rawFileStorage, times(stored - 1)).delete(anyString());
    }

    /** 사람이 처리해 다시 들어오는 경로들도 같은 잠금을 거친다. 자리가 하나면 하나만 들어간다. */
    @Test
    void 자리가_하나일_때_동시에_두_건이_들어오면_하나만_들어간다() throws Exception {
        insertPending(999);

        List<Object> outcomes = runTogether(2, () -> transactionTemplate.execute(status -> {
            guard.requireRoom(org, 1);
            return journalEntryRepository.save(JournalEntry.of(rawRecordId, DATE, "새 일지", 1));
        }));

        assertThat(outcomes).filteredOn(JournalEntry.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(AgentQueueFullException.class::isInstance).hasSize(1);
        assertThat(awaiting()).isEqualTo(1000);
    }

    /**
     * 되돌리기는 요약 행 → 기관 행 순서로 잠근다. 요약 워커는 요약 행만 잠근다. 다른 업로드가 기관 행을 쥐고 있어도 교착 없이 둘 다
     * 끝나고, 결과는 "되돌리기 → 워커" 순서와 같다.
     */
    @Test
    void 기관_행을_쥔_업로드가_있어도_되돌리기와_요약_워커는_교착_없이_끝난다() throws Exception {
        sharingConsentRepository.save(SharingConsent.grant(CHILD, 100L, org, List.of(ConsentField.DAILY_SUMMARY)));
        SummaryResult empty = summaryResultRepository.save(
                SummaryResult.noContent(CHILD, DATE, org, 1, "[]", "{\"no_content\":true}"));
        List<Long> reverted = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            JournalEntry entry = validated(i + 1);
            entry.startSummarizing();
            entry.completeSummary(empty.getId());
            reverted.add(journalEntryRepository.save(entry).getId());
        }
        Long late = journalEntryRepository.save(validated(3)).getId();
        // 시험 설정은 자동 요약이 꺼져 있다 — [요약] 요청이 있어야 워커가 묶음을 집는다.
        runRequests.request(new SummaryGroup(CHILD, DATE, org), LocalDateTime.now());
        String version = Gate1Version.of(summaryResultRepository.findById(empty.getId()).orElseThrow(),
                journalEntryRepository.findBySummaryIdAndDeletedAtIsNull(empty.getId()));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            int holderPid = backendPid(holder);
            try (Statement statement = holder.createStatement()) {
                statement.executeQuery("SELECT id FROM organization WHERE id = " + org + " FOR UPDATE");
            }
            Future<?> revert = executor.submit(() ->
                    gate1Service.decide(TEACHER, empty.getId(), Gate1Action.REVERT, null, version));
            waitUntilBlockedBy(holderPid);
            Future<List<ClaimedSummaryGroup>> claim = executor.submit(() -> summaryService.claimReadyGroups(10));
            Thread.sleep(300);
            holder.commit();

            revert.get(10, TimeUnit.SECONDS);
            List<ClaimedSummaryGroup> claimed = claim.get(10, TimeUnit.SECONDS);

            assertThat(summaryResultRepository.findById(empty.getId()).orElseThrow().getStatus())
                    .isEqualTo(SummaryStatus.REVERTED);
            // 워커는 되돌리기가 커밋한 뒤의 상태로 골랐다 — 되돌린 일지는 요약 대기거나 이번 묶음에 함께 담겼다.
            List<Long> claimedIds = claimed.stream().flatMap(g -> g.journalEntryIds().stream()).toList();
            assertThat(claimedIds).contains(late);
            for (Long id : reverted) {
                JournalEntryStatus status = journalEntryRepository.findById(id).orElseThrow().getStatus();
                assertThat(status).isIn(JournalEntryStatus.VALIDATED, JournalEntryStatus.SUMMARIZING);
                assertThat(status == JournalEntryStatus.SUMMARIZING).isEqualTo(claimedIds.contains(id));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private JournalEntry validated(int seq) {
        JournalEntry entry = JournalEntry.of(rawRecordId, DATE, "특이사항 없음", seq);
        entry.startMatching();
        entry.confirmMatch(CHILD);
        entry.startValidating();
        entry.passValidation();
        return entry;
    }

    private void insertPending(int count) {
        List<JournalEntry> pending = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            pending.add(JournalEntry.of(rawRecordId, DATE, "관찰", i + 1));
        }
        journalEntryRepository.saveAll(pending);
    }

    private long awaiting() {
        return journalEntryRepository.countByInstitutionAndStatusIn(String.valueOf(org), AgentQueueGuard.AWAITING_AGENT);
    }

    private static MockMultipartFile datedTxt(int n) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < n; i++) {
            text.append(LocalDate.of(2026, 1, 1).plusDays(i)).append(" 관찰 ").append(i).append('\n');
        }
        return new MockMultipartFile("file", "note.txt", "text/plain", text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 모두 준비된 뒤 한꺼번에 출발시킨다. 각 결과는 반환값이거나 던진 예외다. */
    private static List<Object> runTogether(int n, Callable<?> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<?> future : futures) {
                try {
                    outcomes.add(future.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    outcomes.add(e.getCause());
                }
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
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
        fail("되돌리기가 기관 행 때문에 멈추지 않았습니다.");
    }
}
