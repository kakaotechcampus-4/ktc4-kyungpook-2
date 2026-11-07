package com.itda.backend.service.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.record.RawRecordStatus;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.domain.summary.SummaryStatus;
import com.itda.backend.dto.request.summary.SummaryAgentRequest;
import com.itda.backend.dto.response.summary.SummaryAgentResponse;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.consent.SharingConsentRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.record.RawRecordRepository;
import com.itda.backend.repository.summary.SummaryResultRepository;

/**
 * 요약 결과를 남기는 일이 동의 철회 · Gate 1 결정과 겹칠 때를 PostgreSQL 에서 확인한다 (#398, 멘토 8차 리뷰 P2).
 *
 * <p>다른 커넥션(홀더)이 행을 잠근 채 커밋하지 않고 잡아 둔다. 결과 저장이 홀더 때문에 멈췄는지
 * {@code pg_blocking_pids} 로 확인한 뒤 홀더를 커밋해, 저장이 잠금을 기다린 다음 최신 상태로 판단하는지 본다.
 * 잠금 순서는 Gate 1 결정과 같은 요약 행 → 아이 행이다 (DB 스키마 §3.3).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class SummaryResultRecorderConcurrencyIntegrationTest {

    private static final Long ORG = 3L;
    private static final LocalDate DATE = LocalDate.of(2026, 10, 8);
    private static final LocalDateTime CLAIMED_AT = LocalDateTime.of(2026, 10, 8, 17, 0);

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("itda_summary_record_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired private SummaryResultRecorder recorder;
    @Autowired private SummaryRunRequests runRequests;
    @Autowired private SummaryResultRepository summaryResultRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private RawRecordRepository rawRecordRepository;
    @Autowired private SharingConsentRepository sharingConsentRepository;
    @Autowired private ChildRepository childRepository;
    @Autowired private DataSource dataSource;

    private Long childId;
    private SummaryGroup group;
    private Long rawRecordId;

    @BeforeEach
    void setUp() {
        // 아이 행이 있어야 잠글 것이 있다.
        childId = childRepository.save(Child.of("김준호", LocalDate.of(2020, 3, 1))).getId();
        group = new SummaryGroup(childId, DATE, ORG);
        sharingConsentRepository.save(SharingConsent.grant(childId, 100L, ORG, List.of(ConsentField.DAILY_SUMMARY)));
        rawRecordId = rawRecordRepository.save(new RawRecord(String.valueOf(ORG), "school.pdf", "kept/school.pdf",
                "application/pdf", 12, RawRecordStatus.PENDING)).getId();
    }

    @AfterEach
    void cleanUp() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM journal_entry");
            statement.executeUpdate("DELETE FROM summary_result");
            statement.executeUpdate("DELETE FROM summary_run_request");
            statement.executeUpdate("DELETE FROM raw_record");
            statement.executeUpdate("DELETE FROM sharing_consent");
            statement.executeUpdate("DELETE FROM child");
        }
    }

    /**
     * 동의 철회(ConsentService)가 아이 행을 잠근 채 커밋하지 않고 있다. 결과 저장은 아이 행에서 기다렸다가 철회가 커밋된
     * 뒤의 동의를 보고 저장하지 않아야 한다. 잠그지 않고 읽으면 커밋 전 값(켜짐)을 보고 철회된 아이의 요약을 남긴다.
     */
    @Test
    void 동의_철회가_아이_행을_잡고_있으면_저장은_기다렸다가_요약을_남기지_않는다() throws Exception {
        JournalEntry entry = journalEntryRepository.save(summarizing("블록 놀이를 했다."));
        runRequests.request(group, CLAIMED_AT.minusMinutes(1));
        SummaryAgentReply reply = reply("블록 놀이를 했다.", entry);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            int holderPid = backendPid(holder);
            try (Statement statement = holder.createStatement()) {
                statement.executeQuery("SELECT id FROM child WHERE id = " + childId + " FOR UPDATE");
                statement.executeUpdate("UPDATE sharing_consent SET status = 'REVOKED', revoked_at = now(), "
                        + "revoke_reason = 'GUARDIAN_REVOKED' WHERE child_id = " + childId);
            }

            Future<?> record = executor.submit(() -> recorder.record(claimed(entry), sent(entry), reply));
            waitUntilBlockedBy(holderPid);
            holder.commit();
            record.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(summaryResultRepository.findAll()).isEmpty();
        assertThat(runRequests.isRequested(group)).isFalse();
        JournalEntry found = journalEntryRepository.findById(entry.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(JournalEntryStatus.VALIDATED);
        assertThat(found.getSummaryId()).isNull();
    }

    /**
     * Gate 1 결정은 요약 행 → 아이 행 순서로 잠근다. 그 요약 행을 쥔 동안 결과 저장이 오면 저장은 아이 행을 잡지 않은 채
     * 요약 행에서 기다려야 한다 — 아이 행을 먼저 잡으면 Gate 1 이 아이 행을 기다리고 저장이 요약 행을 기다려 교착이 된다.
     */
    @Test
    void Gate1_결정처럼_요약_행을_먼저_잡고_아이_행을_잡아도_교착_없이_끝난다() throws Exception {
        SummaryResult pending = summaryResultRepository.save(SummaryResult.of(childId, DATE, ORG, 1,
                "이전 요약", "[]", "[]", "[]", "{}"));
        JournalEntry old = summarizing("이미 요약된 일지");
        old.completeSummary(pending.getId());
        old.startSummarizing();
        JournalEntry resummarizing = journalEntryRepository.save(old);
        SummaryAgentReply reply = reply("다시 만든 요약", resummarizing);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            int holderPid = backendPid(holder);
            try (Statement statement = holder.createStatement()) {
                statement.executeQuery("SELECT id FROM summary_result WHERE id = " + pending.getId() + " FOR UPDATE");
            }

            Future<?> record = executor.submit(
                    () -> recorder.record(claimed(resummarizing), sent(resummarizing), reply));
            waitUntilBlockedBy(holderPid);
            // Gate 1 결정의 다음 단계 — 아이 행을 잠근다. 저장이 아이 행을 쥐고 있으면 여기서 막혀 교착이 된다.
            try (Statement statement = holder.createStatement()) {
                statement.execute("SET LOCAL lock_timeout = '3s'");
                statement.executeQuery("SELECT id FROM child WHERE id = " + childId + " FOR UPDATE");
            } catch (SQLException e) {
                fail("요약 행을 쥔 쪽이 아이 행을 잡지 못했습니다. 결과 저장이 아이 행을 먼저 잠갔을 수 있습니다.", e);
            }
            holder.commit();
            record.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(summaryResultRepository.findAll()).singleElement().satisfies(summary -> {
            assertThat(summary.getId()).isEqualTo(pending.getId());
            assertThat(summary.getContent()).isEqualTo("다시 만든 요약");
            assertThat(summary.getStatus()).isEqualTo(SummaryStatus.GATE1_PENDING);
        });
        assertThat(journalEntryRepository.findById(resummarizing.getId()).orElseThrow().getStatus())
                .isEqualTo(JournalEntryStatus.GATE1_PENDING);
    }

    private JournalEntry summarizing(String content) {
        JournalEntry entry = JournalEntry.of(rawRecordId, DATE, content, 1);
        entry.startMatching();
        entry.confirmMatch(childId);
        entry.startValidating();
        entry.passValidation();
        entry.startSummarizing();
        return entry;
    }

    private ClaimedSummaryGroup claimed(JournalEntry entry) {
        return new ClaimedSummaryGroup(group, List.of(entry.getId()), CLAIMED_AT);
    }

    private SummaryAgentRequest sent(JournalEntry entry) {
        return new SummaryAgentRequest(childId, "김준호", "2026-10-08", ORG, "햇살초등학교",
                List.of(new SummaryAgentRequest.Source(entry.getId(), entry.getContent(), "2026-10-08")), List.of());
    }

    /** 계약대로 만든 AI 응답. 문장 하나가 {@code evidenceFrom} 원문 전체를 인용한다. */
    private SummaryAgentReply reply(String content, JournalEntry evidenceFrom) throws Exception {
        String quote = evidenceFrom.getContent();
        String json = """
                {"child_id": %d, "entry_date": "2026-10-08", "institution_id": %d, "content": "%s",
                 "claims": [{"text": "%s", "evidence": [
                   {"journal_entry_id": %d, "quote": "%s", "span": {"start": 0, "end": %d}}]}],
                 "covered_entry_ids": [%d], "uncovered_entry_ids": [], "llm_called": true}
                """.formatted(childId, ORG, content, content, evidenceFrom.getId(), quote,
                quote.codePointCount(0, quote.length()), evidenceFrom.getId());
        return new SummaryAgentReply(new ObjectMapper().readValue(json, SummaryAgentResponse.class), json);
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
        fail("결과 저장이 홀더 때문에 멈추지 않았습니다. 잠그지 않고 커밋 전 값을 읽었을 수 있습니다.");
    }
}
