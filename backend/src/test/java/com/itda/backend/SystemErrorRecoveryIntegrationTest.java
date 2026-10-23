package com.itda.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import com.itda.backend.domain.record.FailureReason;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.repository.record.JournalEntryRepository;

/** 시스템 오류 되돌리기 psql 스크립트(#180)의 확인 모드와 적용 모드를 실제 PostgreSQL 에서 실행한다. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class SystemErrorRecoveryIntegrationTest {

    private static final String SCRIPT = "recover-system-errors.sql";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("itda_recovery_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired private JournalEntryRepository entries;
    @Autowired private JdbcTemplate jdbc;

    private JournalEntry rejectedInValidation;
    private JournalEntry agentDownInValidation;
    private JournalEntry mismatchInSummary;

    @BeforeEach
    void prepare() {
        entries.deleteAll();
        postgres.copyFileToContainer(MountableFile.forHostPath(Path.of("scripts", SCRIPT).toAbsolutePath()),
                "/tmp/" + SCRIPT);
        rejectedInValidation = entries.saveAndFlush(failedInValidation(FailureReason.REQUEST_REJECTED));
        agentDownInValidation = entries.saveAndFlush(failedInValidation(FailureReason.AGENT_UNAVAILABLE));
        mismatchInSummary = entries.saveAndFlush(failedInSummary(FailureReason.EVIDENCE_MISMATCH));
    }

    private static JournalEntry validating() {
        JournalEntry entry = JournalEntry.of(null, LocalDate.of(2026, 10, 16), "임유진이 블록을 높이 쌓았다.", 1);
        entry.startMatching();
        entry.confirmMatch(8L);
        entry.startValidating();
        return entry;
    }

    private static JournalEntry failedInValidation(FailureReason reason) {
        JournalEntry entry = validating();
        entry.failValidation(reason);
        return entry;
    }

    private static JournalEntry failedInSummary(FailureReason reason) {
        JournalEntry entry = validating();
        entry.passValidation();
        entry.startSummarizing();
        entry.failSummaryBySystemError(reason);
        return entry;
    }

    private ExecResult run(String stage, String reason, boolean apply) throws Exception {
        return postgres.execInContainer("sh", "-c", "cd /tmp && psql -U test -d itda_recovery_test"
                + " -v stage=" + stage + " -v reason=" + reason + " -v apply=" + apply + " -f " + SCRIPT);
    }

    private JournalEntry reload(JournalEntry entry) {
        return entries.findById(entry.getId()).orElseThrow();
    }

    private void assertStillSystemError(JournalEntry entry, FailureReason reason) {
        JournalEntry found = reload(entry);
        assertThat(found.getStatus()).isEqualTo(JournalEntryStatus.SYSTEM_ERROR);
        assertThat(found.getFailureReason()).isEqualTo(reason);
    }

    @Test
    void 확인_모드는_대상만_보여주고_되돌리지_않는다() throws Exception {
        ExecResult result = run("VALIDATION", "REQUEST_REJECTED", false);

        assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();
        assertThat(result.getStdout()).contains("entries_to_recover", "ROLLBACK");
        assertStillSystemError(rejectedInValidation, FailureReason.REQUEST_REJECTED);
    }

    @Test
    void 검증에서_멈춘_일지는_고른_원인만_검증_대기로_되돌리고_실패_원인을_비운다() throws Exception {
        ExecResult result = run("VALIDATION", "REQUEST_REJECTED", true);

        assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();
        JournalEntry recovered = reload(rejectedInValidation);
        assertThat(recovered.getStatus()).isEqualTo(JournalEntryStatus.MATCHED);
        assertThat(recovered.getFailureStage()).isNull();
        assertThat(recovered.getFailureReason()).isNull();
        assertThat(recovered.getAgentRetryCount()).isZero();
        assertThat(recovered.getChildId()).isEqualTo(8L);
        // DB 는 UTC 로 돌 수 있다(배포 DB 가 그렇다). 앱처럼 한국 시간으로 남겨야 다른 행과 비교된다.
        assertThat(recovered.getUpdatedAt())
                .isCloseTo(LocalDateTime.now(ZoneId.of("Asia/Seoul")), within(1, ChronoUnit.MINUTES));
        // 다른 원인·다른 단계는 그대로 둔다.
        assertStillSystemError(agentDownInValidation, FailureReason.AGENT_UNAVAILABLE);
        assertStillSystemError(mismatchInSummary, FailureReason.EVIDENCE_MISMATCH);
    }

    @Test
    void 요약에서_멈춘_일지는_요약_대기로_되돌린다() throws Exception {
        ExecResult result = run("SUMMARY", "EVIDENCE_MISMATCH", true);

        assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();
        JournalEntry recovered = reload(mismatchInSummary);
        assertThat(recovered.getStatus()).isEqualTo(JournalEntryStatus.VALIDATED);
        assertThat(recovered.getFailureStage()).isNull();
        assertThat(recovered.getFailureReason()).isNull();
        assertStillSystemError(rejectedInValidation, FailureReason.REQUEST_REJECTED);
    }

    @Test
    void 아이가_정해지지_않은_일지는_되돌리지_않고_따로_보여준다() throws Exception {
        // 검증 워커는 아이가 없는 일지를 집지 않는다. 검증 대기로 돌리면 화면에서 사라진 채 남는다.
        jdbc.update("update journal_entry set child_id = null, failure_reason = 'INTERNAL_ERROR' where id = ?",
                rejectedInValidation.getId());

        ExecResult result = run("VALIDATION", "INTERNAL_ERROR", true);

        assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();
        assertThat(result.getStdout()).contains("skipped_journal_entry_id_without_child");
        assertStillSystemError(rejectedInValidation, FailureReason.INTERNAL_ERROR);
    }

    @Test
    void 단계나_원인을_잘못_주면_아무것도_바꾸지_않고_멈춘다() throws Exception {
        ExecResult wrongStage = run("MATCHING", "REQUEST_REJECTED", true);
        ExecResult wrongReason = run("VALIDATION", "TYPO", true);
        ExecResult missing = postgres.execInContainer("sh", "-c",
                "cd /tmp && psql -U test -d itda_recovery_test -v apply=true -f " + SCRIPT);

        assertThat(wrongStage.getExitCode()).isNotZero();
        assertThat(wrongReason.getExitCode()).isNotZero();
        assertThat(missing.getExitCode()).isNotZero();
        assertStillSystemError(rejectedInValidation, FailureReason.REQUEST_REJECTED);
        assertStillSystemError(mismatchInSummary, FailureReason.EVIDENCE_MISMATCH);
    }
}
