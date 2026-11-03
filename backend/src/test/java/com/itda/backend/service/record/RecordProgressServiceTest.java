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

import com.itda.backend.domain.record.FailureReason;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.record.RawRecordStatus;
import com.itda.backend.domain.validation.ValidationResult;
import com.itda.backend.domain.validation.ValidationVerdict;
import com.itda.backend.dto.response.record.RecordProgressResponse;
import com.itda.backend.dto.response.record.RecordProgressResponse.EntryProgress;
import com.itda.backend.service.user.UserService;

/** 업로드한 파일이 지금 어디까지 갔는지 (O-26). 읽기만 한다. */
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

    /** 아직 쪼개지 않은 파일(PDF)이다. 화면은 "기록 등록 중" 에 머문다 — 당분간 정상이다. */
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
        entry(recordId, e -> { });                                        // PENDING
        entry(recordId, JournalEntry::startMatching);                     // MATCHING
        entry(recordId, e -> { e.startMatching(); e.requestMatchReview(); });  // MATCH_REVIEW
        entry(recordId, e -> { e.startMatching(); e.confirmMatch(CHILD); });   // MATCHED
        entry(recordId, RecordProgressServiceTest::validated);            // VALIDATED
        entry(recordId, e -> { validated(e); e.startSummarizing(); e.completeSummary(1L); }); // GATE1_PENDING
        entry(recordId, e -> {
            validated(e);
            e.startSummarizing();
            e.completeSummary(1L);
            e.completeGate1();
        });                                                               // COMPLETED

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

    /** 자동 요약이 없어 요약 대기는 교사가 [요약]을 눌러야 넘어간다 (#181). 누른 뒤 요약 중이면 다시 자동이다. */
    @Test
    void 요약_대기는_사람_대기이고_요약_중은_진행_중이다() {
        Long recordId = record(ORG, "0821_관찰일지.txt");
        entry(recordId, RecordProgressServiceTest::validated);                       // VALIDATED
        entry(recordId, e -> { validated(e); e.startSummarizing(); });               // SUMMARIZING

        assertThat(service.getProgress(TEACHER).get(0).entries())
                .extracting(EntryProgress::stageIndex, EntryProgress::state)
                .containsExactly(tuple(3, "waiting"), tuple(3, "running"));
    }

    /** 교사가 끝낸 기록은 아동 맥락에 안 올라가지만 계약의 값이 넷뿐이라 done 으로 간다 (#210). */
    @Test
    void 교사가_끝낸_기록도_done_이다() {
        Long recordId = record(ORG, "0821_관찰일지.txt");
        entry(recordId, e -> { e.startMatching(); e.requestMatchReview(); e.exclude(); });     // EXCLUDED
        entry(recordId, e -> { blocked(e); e.requestReupload(); });        // REUPLOAD_REQUESTED
        entry(recordId, e -> { blocked(e); e.holdAfterValidation(); });    // VALIDATION_HELD

        assertThat(service.getProgress(TEACHER).get(0).entries())
                .extracting(EntryProgress::state)
                .containsOnly("done");
    }

    /**
     * 시스템 오류는 멈춘 단계가 {@code failure_stage} 에 적혀 있다(#180). 요약 실패는 요약 단계다.
     */
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

    /**
     * {@code FAILED} 는 실패한 자리를 적어 두는 칸이 없다. 그대로 0 으로 보내면 요약까지 갔다가 죽은 기록이
     * "등록 단계 실패" 로 보인다 — 일지가 남긴 흔적으로 되짚는다. #180 부터 검증·요약 실패는 FAILED 가 아니지만
     * 그 전에 남은 일지가 있을 수 있다. 지금 코드로는 만들 수 없어 상태를 직접 넣는다.
     *
     * <p>⚠️ <b>검증 실패에도 행이 남는다</b> ({@code verdict = FAILED}, #122). 실제 순서 그대로
     * 재현한다 — 행이 있는지만 보면 검증 실패가 전부 "요약 실패" 로 나온다 (코드리뷰로 확인).
     */
    @Test
    void 옛_FAILED_는_실패한_단계를_일지가_남긴_흔적으로_되짚는다() {
        Long recordId = record(ORG, "0821_관찰일지.txt");

        entry(recordId, e -> { e.startMatching(); e.failMatching(); });

        Long failedInValidation = entry(recordId,
                e -> { e.startMatching(); e.confirmMatch(CHILD); e.startValidating(); });
        validationRow(failedInValidation, ValidationVerdict.FAILED);  // #180 전 ValidationResultRecorder.recordFailure 순서
        markLegacyFailed(failedInValidation);

        // 요약 중에 죽으면 요약 번호가 아직 안 붙는다. 검증을 지났다는 흔적은 PASS 판정뿐이다.
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

    /** 화면이 이 id 로 현황 줄과 큐 항목을 잇는다. 상수를 내려보내도 아무도 모르면 안 된다. */
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
