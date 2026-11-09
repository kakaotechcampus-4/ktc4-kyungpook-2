package com.itda.backend.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import com.itda.backend.domain.record.FailureReason;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.record.RawRecordStatus;
import com.itda.backend.domain.validation.ValidationResult;
import com.itda.backend.domain.validation.ValidationVerdict;
import com.itda.backend.dto.response.record.RecordProgressResponse;
import com.itda.backend.dto.response.record.RecordProgressResponse.EntryProgress;
import com.itda.backend.service.summary.SummaryProperties;
import com.itda.backend.service.user.UserService;

@DataJpaTest
@ActiveProfiles("test")
@Import(RecordProgressService.class)
class RecordProgressServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 21);
    private static final Long ORG = 3L;
    private static final Long OTHER_ORG = 4L;
    private static final Long CHILD = 8L;
    private static final String TEACHER = "99";

    @Autowired
    private TestEntityManager em;

    @Autowired
    private RecordProgressService service;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private SummaryProperties summaryProperties;

    @BeforeEach
    void setUp() {
        given(userService.getOrganizationIdOf(TEACHER)).willReturn(ORG);
    }

    @Test
    void 최근_업로드가_먼저_온다() {
        Long first = record(ORG, "0820_관찰일지.txt");
        Long second = record(ORG, "0821_관찰일지.txt");

        assertThat(service.getProgress(TEACHER))
                .extracting(RecordProgressResponse::rawRecordId)
                .containsExactly(String.valueOf(second), String.valueOf(first));
    }

    @Test
    void 다른_기관이_올린_파일은_보이지_않는다() {
        record(OTHER_ORG, "남의기관.txt");

        assertThat(service.getProgress(TEACHER)).isEmpty();
    }

    @Test
    void 일지로_쪼개지_않은_파일은_entries_가_비어_있다() {
        record(ORG, "0821_관찰일지.pdf");

        assertThat(service.getProgress(TEACHER)).singleElement().satisfies(progress -> {
            assertThat(progress.fileName()).isEqualTo("0821_관찰일지.pdf");
            assertThat(progress.uploadedAt()).isNotNull();
            assertThat(progress.entries()).isEmpty();
        });
    }

    @Test
    void 단계와_상태를_화면_값으로_접는다() {
        Long recordId = record(ORG, "0821_관찰일지.txt");
        entry(recordId, e -> { });
        entry(recordId, JournalEntry::startMatching);
        entry(recordId, e -> { e.startMatching(); e.requestMatchReview(); });
        entry(recordId, e -> { e.startMatching(); e.confirmMatch(CHILD); });
        entry(recordId, RecordProgressServiceTest::validated);
        entry(recordId, e -> { validated(e); e.startSummarizing(); e.completeSummary(1L); });
        entry(recordId, e -> {
            validated(e);
            e.startSummarizing();
            e.completeSummary(1L);
            e.completeGate1();
        });

        assertThat(service.getProgress(TEACHER).get(0).entries())
                .extracting(EntryProgress::stageIndex, EntryProgress::state)
                .containsExactly(
                        tuple(0, "running"),
                        tuple(1, "running"),
                        tuple(1, "waiting"),
                        tuple(2, "running"),
                        tuple(3, "waiting"),
                        tuple(4, "waiting"),
                        tuple(4, "done"));
    }

    @Test
    void 요약_대기는_사람_대기이고_요약_중은_진행_중이다() {
        Long recordId = record(ORG, "0821_관찰일지.txt");
        entry(recordId, RecordProgressServiceTest::validated);
        entry(recordId, e -> { validated(e); e.startSummarizing(); });

        assertThat(service.getProgress(TEACHER).get(0).entries())
                .extracting(EntryProgress::stageIndex, EntryProgress::state)
                .containsExactly(tuple(3, "waiting"), tuple(3, "running"));
    }

    @Test
    void 자동_요약이_켜져_있으면_요약_대기도_진행_중이다() {
        given(summaryProperties.autoRun()).willReturn(true);
        Long recordId = record(ORG, "0821_관찰일지.txt");
        entry(recordId, RecordProgressServiceTest::validated);

        assertThat(service.getProgress(TEACHER).get(0).entries())
                .extracting(EntryProgress::stageIndex, EntryProgress::state)
                .containsExactly(tuple(3, "running"));
    }

    @Test
    void 요약_없이_마무리한_기록은_요약_단계에서_끝난다() {
        Long recordId = record(ORG, "0821_관찰일지.txt");
        entry(recordId, e -> { validated(e); e.startSummarizing(); e.failSummary(); e.closeFailedSummary(); });

        assertThat(service.getProgress(TEACHER).get(0).entries())
                .extracting(EntryProgress::stageIndex, EntryProgress::state)
                .containsExactly(tuple(3, "done"));
    }

    @Test
    void 교사가_끝낸_기록도_done_이다() {
        Long recordId = record(ORG, "0821_관찰일지.txt");
        entry(recordId, e -> { e.startMatching(); e.requestMatchReview(); e.exclude(); });
        entry(recordId, e -> { blocked(e); ReflectionTestUtils.setField(e, "status", JournalEntryStatus.REUPLOAD_REQUESTED); });
        entry(recordId, e -> { blocked(e); e.holdAfterValidation(); });

        assertThat(service.getProgress(TEACHER).get(0).entries())
                .extracting(EntryProgress::state)
                .containsOnly("done");
    }

    @Test
    void 요약_실패와_시스템_오류는_적힌_단계로_보여준다() {
        Long recordId = record(ORG, "0821_관찰일지.txt");
        entry(recordId, e -> {
            e.startMatching();
            e.confirmMatch(CHILD);
            e.startValidating();
            e.failValidation(FailureReason.REQUEST_REJECTED);
        });
        entry(recordId, e -> {
            validated(e);
            e.startSummarizing();
            e.failSummaryBySystemError(FailureReason.EVIDENCE_MISMATCH);
        });
        entry(recordId, e -> { validated(e); e.startSummarizing(); e.failSummary(); });

        assertThat(service.getProgress(TEACHER).get(0).entries())
                .extracting(EntryProgress::stageIndex, EntryProgress::state)
                .containsExactly(
                        tuple(2, "failed"),
                        tuple(3, "failed"),
                        tuple(3, "failed"));
    }

    @Test
    void 옛_FAILED_는_실패한_단계를_일지가_남긴_흔적으로_되짚는다() {
        Long recordId = record(ORG, "0821_관찰일지.txt");

        entry(recordId, e -> { e.startMatching(); e.failMatching(); });

        Long failedInValidation = entry(recordId,
                e -> { e.startMatching(); e.confirmMatch(CHILD); e.startValidating(); });
        validationRow(failedInValidation, ValidationVerdict.FAILED);
        markLegacyFailed(failedInValidation);

        Long failedInSummary = entry(recordId, e -> { validated(e); e.startSummarizing(); });
        validationRow(failedInSummary, ValidationVerdict.PASS);
        markLegacyFailed(failedInSummary);

        assertThat(service.getProgress(TEACHER).get(0).entries())
                .extracting(EntryProgress::stageIndex, EntryProgress::state)
                .containsExactly(
                        tuple(1, "failed"),
                        tuple(2, "failed"),
                        tuple(3, "failed"));
    }

    @Test
    void 일지_id_를_그대로_내려준다() {
        Long recordId = record(ORG, "0821_관찰일지.txt");
        Long first = entry(recordId, e -> { });
        Long second = entry(recordId, JournalEntry::startMatching);

        assertThat(service.getProgress(TEACHER).get(0).entries())
                .extracting(EntryProgress::id)
                .containsExactly(String.valueOf(first), String.valueOf(second));
    }

    @Test
    void 삭제된_일지는_빠진다() {
        Long recordId = record(ORG, "0821_관찰일지.txt");
        entry(recordId, e -> { });
        entry(recordId, JournalEntry::delete);

        assertThat(service.getProgress(TEACHER).get(0).entries()).hasSize(1);
    }

    private void validationRow(Long journalEntryId, ValidationVerdict verdict) {
        em.persistAndFlush(ValidationResult.of(journalEntryId, null, CHILD, verdict, null, null, null));
        em.clear();
    }

    private void markLegacyFailed(Long journalEntryId) {
        em.getEntityManager().createQuery("update JournalEntry e set e.status = :failed where e.id = :id")
                .setParameter("failed", JournalEntryStatus.FAILED)
                .setParameter("id", journalEntryId)
                .executeUpdate();
        em.clear();
    }

    private static void validated(JournalEntry entry) {
        entry.startMatching();
        entry.confirmMatch(CHILD);
        entry.startValidating();
        entry.passValidation();
    }

    private static void blocked(JournalEntry entry) {
        entry.startMatching();
        entry.confirmMatch(CHILD);
        entry.startValidating();
        entry.blockValidation();
    }

    private Long record(Long organizationId, String fileName) {
        return em.persistAndFlush(new RawRecord(String.valueOf(organizationId), fileName,
                "kept/" + fileName, "text/plain", 12, RawRecordStatus.PENDING)).getId();
    }

    private Long entry(Long rawRecordId, Consumer<JournalEntry> transitions) {
        JournalEntry entry = JournalEntry.of(rawRecordId, DATE, "블록 놀이를 했다.", 1);
        transitions.accept(entry);
        Long id = em.persistAndFlush(entry).getId();
        em.clear();
        return id;
    }
}
