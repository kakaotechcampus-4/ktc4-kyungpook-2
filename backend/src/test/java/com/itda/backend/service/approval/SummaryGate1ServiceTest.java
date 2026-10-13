package com.itda.backend.service.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.itda.backend.domain.approval.Approval;
import com.itda.backend.domain.approval.ApprovalDecision;
import com.itda.backend.domain.approval.ApprovalReviewType;
import com.itda.backend.domain.approval.ApprovalTargetType;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.record.RawRecordStatus;
import com.itda.backend.domain.summary.ChildContext;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.domain.summary.SummaryStatus;
import com.itda.backend.dto.response.summary.Gate1DecisionResponse;
import com.itda.backend.exception.summary.SummaryConflictException;
import com.itda.backend.exception.summary.SummaryErrorCode;
import com.itda.backend.exception.summary.SummaryNotFoundException;
import com.itda.backend.repository.approval.ApprovalRepository;
import com.itda.backend.repository.summary.ChildContextRepository;
import com.itda.backend.service.user.UserService;

/** Gate 1 — 교사가 요약을 승인·수정 후 승인·보류·제거하고, 보류함에서 보류를 해제한다. */
@DataJpaTest
@ActiveProfiles("test")
@Import(SummaryGate1Service.class)
class SummaryGate1ServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 8);
    private static final Long ORG = 3L;
    private static final Long OTHER_ORG = 4L;
    private static final Long CHILD = 8L;
    private static final String TEACHER = "99";
    private static final String AI_TEXT = "블록 놀이에서 친구에게 양보했다.";

    @Autowired
    private TestEntityManager em;

    @Autowired
    private SummaryGate1Service service;

    @Autowired
    private ChildContextRepository childContextRepository;

    @Autowired
    private ApprovalRepository approvalRepository;

    @MockitoBean
    private UserService userService;

    private Long rawRecordId;
    private SummaryResult summary;
    private JournalEntry covered;
    private JournalEntry uncovered;

    @BeforeEach
    void setUp() {
        given(userService.getOrganizationIdOf(TEACHER)).willReturn(ORG);
        rawRecordId = em.persist(new RawRecord(String.valueOf(ORG), "school.pdf", "kept/school.pdf",
                "application/pdf", 12, RawRecordStatus.PENDING)).getId();
        summary = summaryOf(ORG);
        covered = entryIn(summary);
        uncovered = entryIn(summary);
        em.flush();
        em.clear();
    }

    private SummaryResult summaryOf(Long institutionId) {
        return em.persistAndFlush(SummaryResult.of(CHILD, DATE, institutionId, 1, AI_TEXT, "[]",
                "[]", "[]", "{}"));
    }

    private JournalEntry entryIn(SummaryResult target) {
        JournalEntry entry = validated();
        entry.startSummarizing();
        entry.completeSummary(target.getId());
        return em.persistAndFlush(entry);
    }

    private JournalEntry validated() {
        JournalEntry entry = JournalEntry.of(rawRecordId, DATE, "블록 놀이를 했다.", 1);
        entry.startMatching();
        entry.confirmMatch(CHILD);
        entry.startValidating();
        entry.passValidation();
        return entry;
    }

    /** 같은 묶음의 새 일지만 워커가 집어 간 상태. 이 요약의 일지는 하나도 집히지 않았다. */
    private void newEntrySummarizingInSameGroup() {
        JournalEntry fresh = validated();
        fresh.startSummarizing();
        em.persistAndFlush(fresh);
        em.clear();
    }

    private SummaryResult reloadSummary() {
        em.flush();
        em.clear();
        return em.find(SummaryResult.class, summary.getId());
    }

    private JournalEntry reload(JournalEntry entry) {
        em.flush();
        em.clear();
        return em.find(JournalEntry.class, entry.getId());
    }

    private List<Approval> history() {
        return approvalRepository.findByTargetTypeAndTargetId(ApprovalTargetType.SUMMARY, summary.getId());
    }

    // ── 승인 ──

    @Test
    void 그대로_승인하면_요약은_승인되고_AI_원문이_아동_맥락으로_올라간다() {
        Gate1DecisionResponse response = service.decide(TEACHER, summary.getId(), Gate1Action.APPROVE, null);

        assertThat(response.summaryId()).isEqualTo(summary.getId());
        assertThat(response.decision()).isEqualTo("APPROVED");
        assertThat(reloadSummary().getStatus()).isEqualTo(SummaryStatus.APPROVED);
        List<ChildContext> contexts = childContextRepository.findByChildIdOrderByIdDesc(CHILD);
        assertThat(contexts).singleElement().satisfies(c -> {
            assertThat(c.getSummaryResultId()).isEqualTo(summary.getId());
            assertThat(c.getContent()).isEqualTo(AI_TEXT);
        });
        assertThat(history()).singleElement().satisfies(h -> {
            assertThat(h.getReviewType()).isEqualTo(ApprovalReviewType.GATE1);
            assertThat(h.getReviewerId()).isEqualTo(99L);
            assertThat(h.getDecision()).isEqualTo(ApprovalDecision.APPROVED);
            assertThat(h.getComment()).isNull();
        });
    }

    @Test
    void 원문과_같은_본문을_보내면_그대로_승인으로_본다() {
        service.decide(TEACHER, summary.getId(), Gate1Action.APPROVE, AI_TEXT);

        assertThat(history()).singleElement()
                .satisfies(h -> assertThat(h.getDecision()).isEqualTo(ApprovalDecision.APPROVED));
    }

    @Test
    void 한_글자라도_고쳐_승인하면_수정_후_승인이고_고친_본문이_올라간다() {
        String edited = AI_TEXT.replace("양보했다.", "양보했다!");

        Gate1DecisionResponse response = service.decide(TEACHER, summary.getId(), Gate1Action.APPROVE, edited);

        assertThat(response.decision()).isEqualTo("CORRECTED");
        SummaryResult reloaded = reloadSummary();
        assertThat(reloaded.getStatus()).isEqualTo(SummaryStatus.APPROVED);
        // AI 원문은 그대로 남긴다 — 고친 본문은 child_context 에만 있다.
        assertThat(reloaded.getContent()).isEqualTo(AI_TEXT);
        assertThat(childContextRepository.findByChildIdOrderByIdDesc(CHILD)).singleElement()
                .satisfies(c -> assertThat(c.getContent()).isEqualTo(edited));
        assertThat(history()).singleElement()
                .satisfies(h -> assertThat(h.getDecision()).isEqualTo(ApprovalDecision.CORRECTED));
    }

    @Test
    void 승인하면_요약에_안_들어간_일지까지_그_요약의_일지가_모두_완료된다() {
        service.decide(TEACHER, summary.getId(), Gate1Action.APPROVE, null);

        assertThat(reload(covered).getStatus()).isEqualTo(JournalEntryStatus.COMPLETED);
        assertThat(reload(uncovered).getStatus()).isEqualTo(JournalEntryStatus.COMPLETED);
    }

    // ── 보류 ──

    @Test
    void 보류하면_요약과_그_일지가_보류된다() {
        Gate1DecisionResponse response = service.decide(TEACHER, summary.getId(), Gate1Action.HOLD, null);

        assertThat(response.decision()).isEqualTo("HOLD");
        assertThat(reloadSummary().getStatus()).isEqualTo(SummaryStatus.HOLD);
        assertThat(reload(covered).getStatus()).isEqualTo(JournalEntryStatus.GATE1_HELD);
        assertThat(reload(uncovered).getStatus()).isEqualTo(JournalEntryStatus.GATE1_HELD);
        assertThat(childContextRepository.findByChildIdOrderByIdDesc(CHILD)).isEmpty();
        assertThat(history()).singleElement()
                .satisfies(h -> assertThat(h.getDecision()).isEqualTo(ApprovalDecision.HOLD));
    }

    // ── 제거 ──

    @Test
    void 제거하면_요약은_제거되고_그_일지는_모두_삭제_표시된다() {
        Gate1DecisionResponse response = service.decide(TEACHER, summary.getId(), Gate1Action.REMOVE, null);

        assertThat(response.decision()).isEqualTo("REMOVED");
        assertThat(reloadSummary().getStatus()).isEqualTo(SummaryStatus.REMOVED);
        assertThat(reload(covered).isDeleted()).isTrue();
        assertThat(reload(uncovered).isDeleted()).isTrue();
        assertThat(childContextRepository.findByChildIdOrderByIdDesc(CHILD)).isEmpty();
        assertThat(history()).singleElement()
                .satisfies(h -> assertThat(h.getDecision()).isEqualTo(ApprovalDecision.REMOVED));
    }

    // ── 권한·중복 ──

    @Test
    void 다른_기관의_요약은_없는_것으로_응답한다() {
        SummaryResult others = summaryOf(OTHER_ORG);

        assertThatThrownBy(() -> service.decide(TEACHER, others.getId(), Gate1Action.APPROVE, null))
                .isInstanceOf(SummaryNotFoundException.class);
        assertThatThrownBy(() -> service.decide(TEACHER, 9999L, Gate1Action.APPROVE, null))
                .isInstanceOf(SummaryNotFoundException.class);
    }

    @Test
    void 이미_처리된_요약은_다시_결정할_수_없다() {
        service.decide(TEACHER, summary.getId(), Gate1Action.APPROVE, null);
        em.flush();
        em.clear();

        for (Gate1Action action : Gate1Action.values()) {
            assertThatThrownBy(() -> service.decide(TEACHER, summary.getId(), action, null))
                    .isInstanceOf(SummaryConflictException.class)
                    .extracting(e -> ((SummaryConflictException) e).getErrorCode())
                    .isEqualTo(SummaryErrorCode.ALREADY_REVIEWED);
        }
        assertThat(history()).hasSize(1);
    }

    @Test
    void 새_일지와_함께_다시_요약하는_중이면_결정할_수_없다() {
        // 워커가 이 요약의 일지를 새 일지와 함께 집어 갔다. 승인하면 곧 덮어써질 글을 승인하게 된다.
        JournalEntry resummarizing = em.find(JournalEntry.class, covered.getId());
        resummarizing.startSummarizing();
        em.flush();
        em.clear();

        assertThatThrownBy(() -> service.decide(TEACHER, summary.getId(), Gate1Action.APPROVE, null))
                .isInstanceOf(SummaryConflictException.class)
                .extracting(e -> ((SummaryConflictException) e).getErrorCode())
                .isEqualTo(SummaryErrorCode.REGENERATING);
        assertThat(reloadSummary().getStatus()).isEqualTo(SummaryStatus.GATE1_PENDING);
    }

    // ── 보류 해제 ──

    @Test
    void 보류를_해제하면_요약과_그_일지가_다시_Gate1_대기가_된다() {
        service.decide(TEACHER, summary.getId(), Gate1Action.HOLD, null);
        em.flush();
        em.clear();

        Gate1DecisionResponse response = service.reopen(TEACHER, summary.getId());

        assertThat(response.status()).isEqualTo("GATE1_PENDING");
        assertThat(response.decision()).isEqualTo("REOPENED");
        assertThat(reloadSummary().getStatus()).isEqualTo(SummaryStatus.GATE1_PENDING);
        assertThat(reload(covered).getStatus()).isEqualTo(JournalEntryStatus.GATE1_PENDING);
        assertThat(reload(uncovered).getStatus()).isEqualTo(JournalEntryStatus.GATE1_PENDING);
        assertThat(history()).extracting(Approval::getDecision)
                .containsExactlyInAnyOrder(ApprovalDecision.HOLD, ApprovalDecision.REOPENED);
    }

    @Test
    void 보류된_요약이_아니면_보류_해제할_수_없다() {
        assertThatThrownBy(() -> service.reopen(TEACHER, summary.getId()))
                .isInstanceOf(SummaryConflictException.class)
                .extracting(e -> ((SummaryConflictException) e).getErrorCode())
                .isEqualTo(SummaryErrorCode.NOT_HELD);
        assertThat(history()).isEmpty();
    }

    @Test
    void 다른_기관의_요약은_보류_해제할_수_없다() {
        SummaryResult others = summaryOf(OTHER_ORG);
        others.hold();
        em.flush();

        assertThatThrownBy(() -> service.reopen(TEACHER, others.getId()))
                .isInstanceOf(SummaryNotFoundException.class);
    }

    @Test
    void 이_요약의_일지가_집히지_않았어도_같은_묶음을_요약하는_중이면_결정할_수_없다() {
        // 이 요약의 일지가 모두 지워졌거나, 다른 판의 일지만 집힌 경우다. 요약 결과는 최신 판을 덮어쓸 수 있으므로
        // 묶음 단위로 막는다.
        newEntrySummarizingInSameGroup();

        assertThatThrownBy(() -> service.decide(TEACHER, summary.getId(), Gate1Action.APPROVE, null))
                .isInstanceOf(SummaryConflictException.class)
                .extracting(e -> ((SummaryConflictException) e).getErrorCode())
                .isEqualTo(SummaryErrorCode.REGENERATING);
    }

    @Test
    void 같은_묶음을_요약하는_중이면_보류를_해제할_수_없다() {
        // 보류한 판이 최신이라 워커는 새 일지만 집었다. 지금 해제하면 요약 결과가 이 판을 새 일지만으로 덮어써
        // 이 판의 일지가 요약에서 사라진 채 승인될 수 있다.
        service.decide(TEACHER, summary.getId(), Gate1Action.HOLD, null);
        em.flush();
        newEntrySummarizingInSameGroup();

        assertThatThrownBy(() -> service.reopen(TEACHER, summary.getId()))
                .isInstanceOf(SummaryConflictException.class)
                .extracting(e -> ((SummaryConflictException) e).getErrorCode())
                .isEqualTo(SummaryErrorCode.REGENERATING);
        assertThat(reloadSummary().getStatus()).isEqualTo(SummaryStatus.HOLD);
    }
}

