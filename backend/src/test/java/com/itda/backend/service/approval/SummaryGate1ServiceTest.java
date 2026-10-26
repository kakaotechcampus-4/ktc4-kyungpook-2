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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.domain.approval.Approval;
import com.itda.backend.domain.approval.ApprovalDecision;
import com.itda.backend.domain.approval.Gate1Action;
import com.itda.backend.domain.approval.ApprovalReviewType;
import com.itda.backend.domain.approval.ApprovalTargetType;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.record.RawRecordStatus;
import com.itda.backend.domain.summary.ChildContext;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.domain.summary.SummaryStatus;
import com.itda.backend.dto.request.summary.Gate1DecisionRequest.Card;
import com.itda.backend.dto.response.summary.Gate1DecisionResponse;
import com.itda.backend.exception.summary.SummaryException;
import com.itda.backend.exception.summary.SummaryErrorCode;
import com.itda.backend.repository.approval.ApprovalRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.summary.ChildContextRepository;
import com.itda.backend.repository.summary.SummaryResultRepository;
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
    private static final String FIRST = "블록 놀이에서 친구에게 양보했다.";
    private static final String SECOND = "낮잠을 잘 잤다.";
    private static final String AI_TEXT = FIRST + " " + SECOND;
    /** 문장마다 근거가 있는 AI 원문 claims. AI 는 source 를 보내지 않는다. */
    private static final String AI_CLAIMS = "["
            + "{\"text\":\"" + FIRST + "\",\"evidence\":[{\"journal_entry_id\":1041,\"quote\":\"양보\","
            + "\"span\":{\"start\":7,\"end\":9}}]},"
            + "{\"text\":\"" + SECOND + "\",\"evidence\":[{\"journal_entry_id\":1042,\"quote\":\"낮잠\","
            + "\"span\":{\"start\":0,\"end\":2}}]}]";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TestEntityManager em;

    @Autowired
    private SummaryGate1Service service;

    @Autowired
    private ChildContextRepository childContextRepository;

    @Autowired
    private ApprovalRepository approvalRepository;

    @Autowired
    private SummaryResultRepository summaryResultRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

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
        return em.persistAndFlush(SummaryResult.of(CHILD, DATE, institutionId, 1, AI_TEXT, AI_CLAIMS,
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

    /** 교사가 목록에서 받은 판 표시. 결정할 때 그대로 돌려보낸다. */
    private String versionOf(Long summaryId) {
        return Gate1Version.of(summaryResultRepository.findById(summaryId).orElseThrow(),
                journalEntryRepository.findBySummaryIdAndDeletedAtIsNull(summaryId));
    }

    private Gate1DecisionResponse approveWith(Card... cards) {
        return service.decide(TEACHER, summary.getId(), Gate1Action.APPROVE, List.of(cards),
                versionOf(summary.getId()));
    }

    private static Card keep(int index) {
        return new Card(index, "KEEP", null);
    }

    private static Card delete(int index) {
        return new Card(index, "DELETE", null);
    }

    private static Card edit(int index, String text) {
        return new Card(index, "EDIT", text);
    }

    private static Card add(String text) {
        return new Card(null, "ADD", text);
    }

    private JsonNode savedClaims() throws Exception {
        return MAPPER.readTree(reloadSummary().getClaims());
    }

    private String approvedContent() {
        return childContextRepository.findByChildIdOrderByIdDesc(CHILD).get(0).getContent();
    }

    private List<Approval> history() {
        return approvalRepository.findByTargetTypeAndTargetId(ApprovalTargetType.SUMMARY, summary.getId());
    }

    // ── 승인 ──

    @Test
    void 그대로_승인하면_요약은_승인되고_AI_원문이_아동_맥락으로_올라간다() {
        Gate1DecisionResponse response = service.decide(TEACHER, summary.getId(), Gate1Action.APPROVE, null,
                versionOf(summary.getId()));

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

    // ── 문장 카드 ──

    @Test
    void 카드를_모두_유지하면_그대로_승인이고_claims_는_바뀌지_않는다() throws Exception {
        Gate1DecisionResponse response = approveWith(keep(0), keep(1));

        assertThat(response.decision()).isEqualTo("APPROVED");
        assertThat(reloadSummary().getClaims()).isEqualTo(AI_CLAIMS);
        assertThat(approvedContent()).isEqualTo(AI_TEXT);
    }

    @Test
    void 문장을_지우면_수정_후_승인이고_claims_에서_빠진다() throws Exception {
        Gate1DecisionResponse response = approveWith(keep(0), delete(1));

        assertThat(response.decision()).isEqualTo("CORRECTED");
        JsonNode claims = savedClaims();
        assertThat(claims).hasSize(1);
        assertThat(claims.get(0).path("text").asText()).isEqualTo(FIRST);
        // 고치지 않은 문장도 source 를 비워 두지 않는다 — 인사이트가 누가 쓴 문장인지 이 값으로 가른다.
        assertThat(claims.get(0).path("source").asText()).isEqualTo("AI");
        assertThat(claims.get(0).path("evidence").get(0).path("journal_entry_id").asLong()).isEqualTo(1041L);
        assertThat(approvedContent()).isEqualTo(FIRST);
        // AI 원문은 요약 행에 그대로 남는다.
        assertThat(reloadSummary().getContent()).isEqualTo(AI_TEXT);
        assertThat(history()).singleElement()
                .satisfies(h -> assertThat(h.getDecision()).isEqualTo(ApprovalDecision.CORRECTED));
    }

    @Test
    void 문장을_고치면_글만_바뀌고_근거는_그대로_남는다() throws Exception {
        String edited = "낮잠을 한 시간 잤다.";

        Gate1DecisionResponse response = approveWith(keep(0), edit(1, edited));

        assertThat(response.decision()).isEqualTo("CORRECTED");
        JsonNode claim = savedClaims().get(1);
        assertThat(claim.path("text").asText()).isEqualTo(edited);
        assertThat(claim.path("source").asText()).isEqualTo("EDITED");
        assertThat(claim.path("evidence").get(0).path("journal_entry_id").asLong()).isEqualTo(1042L);
        assertThat(claim.path("evidence").get(0).path("quote").asText()).isEqualTo("낮잠");
        assertThat(approvedContent()).isEqualTo(FIRST + " " + edited);
    }

    @Test
    void 새로_쓴_문장은_교사_문장으로_일지_번호_없이_그_문장을_근거로_남긴다() throws Exception {
        String added = "약 먹은 날 차분했다.";

        approveWith(keep(0), keep(1), add(added));

        JsonNode claim = savedClaims().get(2);
        assertThat(claim.path("text").asText()).isEqualTo(added);
        assertThat(claim.path("source").asText()).isEqualTo("TEACHER");
        JsonNode evidence = claim.path("evidence");
        assertThat(evidence).hasSize(1);
        // 키를 빼지 않고 null 로 둔다 — 인사이트 계약이 TEACHER 근거의 일지 번호를 null 로 받는다.
        assertThat(evidence.get(0).has("journal_entry_id")).isTrue();
        assertThat(evidence.get(0).path("journal_entry_id").isNull()).isTrue();
        assertThat(evidence.get(0).path("quote").asText()).isEqualTo(added);
        assertThat(approvedContent()).isEqualTo(AI_TEXT + " " + added);
    }

    @Test
    void 순서만_바꿔도_수정_후_승인이고_카드_순서대로_저장한다() throws Exception {
        Gate1DecisionResponse response = approveWith(keep(1), keep(0));

        assertThat(response.decision()).isEqualTo("CORRECTED");
        JsonNode claims = savedClaims();
        assertThat(claims.get(0).path("text").asText()).isEqualTo(SECOND);
        assertThat(claims.get(1).path("text").asText()).isEqualTo(FIRST);
        assertThat(approvedContent()).isEqualTo(SECOND + " " + FIRST);
    }

    @Test
    void 고친_글이_원문과_줄바꿈이나_앞뒤_공백만_다르면_고친_것으로_보지_않는다() {
        // 입력창을 거치면 끝에 줄바꿈이 붙거나 CRLF 로 바뀌곤 한다. 고친 것으로 보면 근거 없는 수정이 남는다.
        Gate1DecisionResponse response = approveWith(edit(0, " " + FIRST + "\r\n"), keep(1));

        assertThat(response.decision()).isEqualTo("APPROVED");
        assertThat(reloadSummary().getClaims()).isEqualTo(AI_CLAIMS);
        assertThat(approvedContent()).isEqualTo(AI_TEXT);
    }

    @Test
    void 고친_글과_새_글은_앞뒤_공백을_지워_저장한다() throws Exception {
        approveWith(edit(0, "  양보했다.\r\n"), keep(1), add("\n차분했다. "));

        JsonNode claims = savedClaims();
        assertThat(claims.get(0).path("text").asText()).isEqualTo("양보했다.");
        assertThat(claims.get(2).path("text").asText()).isEqualTo("차분했다.");
        assertThat(claims.get(2).path("evidence").get(0).path("quote").asText()).isEqualTo("차분했다.");
    }

    @Test
    void 글이_빈_문장은_본문에_이어_붙이지_않는다() throws Exception {
        SummaryResult blank = em.persistAndFlush(SummaryResult.of(CHILD, DATE.plusDays(1), ORG, 1, FIRST,
                "[{\"text\":\"" + FIRST + "\",\"evidence\":[]},{\"text\":\" \",\"evidence\":[]}]",
                "[]", "[]", "{}"));

        service.decide(TEACHER, blank.getId(), Gate1Action.APPROVE,
                List.of(edit(0, "양보했다."), keep(1), add("차분했다.")), versionOf(blank.getId()));

        assertThat(childContextRepository.findBySummaryResultIdIn(List.of(blank.getId()))).singleElement()
                .satisfies(c -> assertThat(c.getContent()).isEqualTo("양보했다. 차분했다."));
    }

    @Test
    void 원래_문장을_모두_한_번씩_보내지_않으면_받지_않는다() {
        List<List<Card>> invalid = List.of(
                List.of(keep(0)),                     // 빠짐
                List.of(keep(0), keep(1), keep(1)),   // 중복
                List.of(keep(0), keep(1), keep(2)),   // 범위 밖
                List.of(keep(0), edit(0, "x"), keep(1)),
                List.of(keep(-1), keep(0), keep(1)));
        for (List<Card> cards : invalid) {
            assertThatThrownBy(() -> service.decide(TEACHER, summary.getId(), Gate1Action.APPROVE, cards,
                    versionOf(summary.getId())))
                    .isInstanceOf(SummaryException.class)
                    .extracting(e -> ((SummaryException) e).getErrorCode())
                    .isEqualTo(SummaryErrorCode.INVALID_REQUEST);
        }
        assertThat(reloadSummary().getStatus()).isEqualTo(SummaryStatus.GATE1_PENDING);
        assertThat(childContextRepository.findByChildIdOrderByIdDesc(CHILD)).isEmpty();
        assertThat(history()).isEmpty();
    }

    @Test
    void 남는_문장이_없으면_받지_않는다_모두_지우려면_제거한다() {
        assertThatThrownBy(() -> approveWith(delete(0), delete(1)))
                .isInstanceOf(SummaryException.class)
                .extracting(e -> ((SummaryException) e).getErrorCode())
                .isEqualTo(SummaryErrorCode.INVALID_REQUEST);
        assertThat(reloadSummary().getStatus()).isEqualTo(SummaryStatus.GATE1_PENDING);
    }

    @Test
    void 문장을_못_읽는_요약은_카드로_승인할_수_없다_AI_원문이_통째로_사라진다() {
        String[] unreadable = {null, "", "{}", "[\"문장\"]"};
        for (int day = 0; day < unreadable.length; day++) {
            String claims = unreadable[day];
            summary = em.persistAndFlush(SummaryResult.of(CHILD, DATE.plusDays(day + 1), ORG, 1, AI_TEXT, claims,
                    "[]", "[]", "{}"));
            entryIn(summary);
            em.clear();

            assertThatThrownBy(() -> approveWith(add("교사가 쓴 문장.")))
                    .isInstanceOf(SummaryException.class)
                    .extracting(e -> ((SummaryException) e).getErrorCode())
                    .isEqualTo(SummaryErrorCode.INVALID_REQUEST);
            assertThat(reloadSummary().getStatus()).isEqualTo(SummaryStatus.GATE1_PENDING);
        }
    }

    @Test
    void 모두_지우고_새로_쓴_문장만_남겨도_승인한다() throws Exception {
        approveWith(delete(0), delete(1), add("교사가 쓴 문장."));

        JsonNode claims = savedClaims();
        assertThat(claims).hasSize(1);
        assertThat(claims.get(0).path("source").asText()).isEqualTo("TEACHER");
        assertThat(approvedContent()).isEqualTo("교사가 쓴 문장.");
    }

    @Test
    void 승인하면_요약에_안_들어간_일지까지_그_요약의_일지가_모두_완료된다() {
        service.decide(TEACHER, summary.getId(), Gate1Action.APPROVE, null, versionOf(summary.getId()));

        assertThat(reload(covered).getStatus()).isEqualTo(JournalEntryStatus.COMPLETED);
        assertThat(reload(uncovered).getStatus()).isEqualTo(JournalEntryStatus.COMPLETED);
    }

    // ── 보류 ──

    @Test
    void 보류하면_요약과_그_일지가_보류된다() {
        Gate1DecisionResponse response = service.decide(TEACHER, summary.getId(), Gate1Action.HOLD, null,
                versionOf(summary.getId()));

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
        Gate1DecisionResponse response = service.decide(TEACHER, summary.getId(), Gate1Action.REMOVE, null,
                versionOf(summary.getId()));

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

        assertThatThrownBy(() -> service.decide(TEACHER, others.getId(), Gate1Action.APPROVE, null,
                versionOf(others.getId())))
                .isInstanceOf(SummaryException.class)
                .extracting(e -> ((SummaryException) e).getErrorCode())
                .isEqualTo(SummaryErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> service.decide(TEACHER, 9999L, Gate1Action.APPROVE, null, "v"))
                .isInstanceOf(SummaryException.class)
                .extracting(e -> ((SummaryException) e).getErrorCode())
                .isEqualTo(SummaryErrorCode.NOT_FOUND);
    }

    @Test
    void 이미_처리된_요약은_다시_결정할_수_없다() {
        service.decide(TEACHER, summary.getId(), Gate1Action.APPROVE, null, versionOf(summary.getId()));
        em.flush();
        em.clear();

        for (Gate1Action action : Gate1Action.values()) {
            assertThatThrownBy(() -> service.decide(TEACHER, summary.getId(), action, null, versionOf(summary.getId())))
                    .isInstanceOf(SummaryException.class)
                    .extracting(e -> ((SummaryException) e).getErrorCode())
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

        assertThatThrownBy(() -> service.decide(TEACHER, summary.getId(), Gate1Action.APPROVE, null,
                versionOf(summary.getId())))
                .isInstanceOf(SummaryException.class)
                .extracting(e -> ((SummaryException) e).getErrorCode())
                .isEqualTo(SummaryErrorCode.REGENERATING);
        assertThat(reloadSummary().getStatus()).isEqualTo(SummaryStatus.GATE1_PENDING);
    }

    // ── 보류 해제 ──

    @Test
    void 보류를_해제하면_요약과_그_일지가_다시_Gate1_대기가_된다() {
        service.decide(TEACHER, summary.getId(), Gate1Action.HOLD, null, versionOf(summary.getId()));
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
                .isInstanceOf(SummaryException.class)
                .extracting(e -> ((SummaryException) e).getErrorCode())
                .isEqualTo(SummaryErrorCode.NOT_HELD);
        assertThat(history()).isEmpty();
    }

    @Test
    void 다른_기관의_요약은_보류_해제할_수_없다() {
        SummaryResult others = summaryOf(OTHER_ORG);
        others.hold();
        em.flush();

        assertThatThrownBy(() -> service.reopen(TEACHER, others.getId()))
                .isInstanceOf(SummaryException.class)
                .extracting(e -> ((SummaryException) e).getErrorCode())
                .isEqualTo(SummaryErrorCode.NOT_FOUND);
    }

    @Test
    void 이_요약의_일지가_집히지_않았어도_같은_묶음을_요약하는_중이면_결정할_수_없다() {
        // 이 요약의 일지가 모두 지워졌거나, 다른 판의 일지만 집힌 경우다. 요약 결과는 최신 판을 덮어쓸 수 있으므로
        // 묶음 단위로 막는다.
        newEntrySummarizingInSameGroup();

        assertThatThrownBy(() -> service.decide(TEACHER, summary.getId(), Gate1Action.APPROVE, null,
                versionOf(summary.getId())))
                .isInstanceOf(SummaryException.class)
                .extracting(e -> ((SummaryException) e).getErrorCode())
                .isEqualTo(SummaryErrorCode.REGENERATING);
    }

    @Test
    void 같은_묶음을_요약하는_중이면_보류를_해제할_수_없다() {
        // 보류한 판이 최신이라 워커는 새 일지만 집었다. 지금 해제하면 요약 결과가 이 판을 새 일지만으로 덮어써
        // 이 판의 일지가 요약에서 사라진 채 승인될 수 있다.
        service.decide(TEACHER, summary.getId(), Gate1Action.HOLD, null, versionOf(summary.getId()));
        em.flush();
        newEntrySummarizingInSameGroup();

        assertThatThrownBy(() -> service.reopen(TEACHER, summary.getId()))
                .isInstanceOf(SummaryException.class)
                .extracting(e -> ((SummaryException) e).getErrorCode())
                .isEqualTo(SummaryErrorCode.REGENERATING);
        assertThat(reloadSummary().getStatus()).isEqualTo(SummaryStatus.HOLD);
    }

    // ── 교사가 본 판 ──

    @Test
    void 교사가_목록을_연_뒤_요약이_새_일지로_다시_만들어졌으면_결정할_수_없다() {
        String seen = versionOf(summary.getId());
        // 그 사이 같은 날 일지가 들어와 워커가 같은 요약을 덮어썼다. id 와 판 번호는 그대로다.
        SummaryResult overwritten = em.find(SummaryResult.class, summary.getId());
        overwritten.overwrite("새 일지까지 반영한 요약", "[]", "[]", "[]", "{}");
        em.flush();
        em.clear();

        for (Gate1Action action : Gate1Action.values()) {
            assertThatThrownBy(() -> service.decide(TEACHER, summary.getId(), action, null, seen))
                    .isInstanceOf(SummaryException.class)
                    .extracting(e -> ((SummaryException) e).getErrorCode())
                    .isEqualTo(SummaryErrorCode.CHANGED);
        }
        assertThat(reloadSummary().getStatus()).isEqualTo(SummaryStatus.GATE1_PENDING);
        assertThat(history()).isEmpty();
    }

    // ── 보류함 ──

    @Test
    void 보류함에서_바로_제거하면_요약과_그_일지가_지워진다() {
        service.decide(TEACHER, summary.getId(), Gate1Action.HOLD, null, versionOf(summary.getId()));
        em.flush();
        em.clear();

        Gate1DecisionResponse response = service.decide(TEACHER, summary.getId(), Gate1Action.REMOVE, null,
                versionOf(summary.getId()));

        assertThat(response.status()).isEqualTo("REMOVED");
        assertThat(reload(covered).isDeleted()).isTrue();
        assertThat(reload(uncovered).isDeleted()).isTrue();
        assertThat(history()).extracting(Approval::getDecision)
                .containsExactlyInAnyOrder(ApprovalDecision.HOLD, ApprovalDecision.REMOVED);
    }

    @Test
    void 보류된_요약은_보류를_해제한_뒤에_승인한다() {
        service.decide(TEACHER, summary.getId(), Gate1Action.HOLD, null, versionOf(summary.getId()));
        em.flush();
        em.clear();

        for (Gate1Action action : new Gate1Action[] {Gate1Action.APPROVE, Gate1Action.HOLD}) {
            assertThatThrownBy(() -> service.decide(TEACHER, summary.getId(), action, null,
                    versionOf(summary.getId())))
                    .isInstanceOf(SummaryException.class)
                    .extracting(e -> ((SummaryException) e).getErrorCode())
                    .isEqualTo(SummaryErrorCode.HELD);
        }
        assertThat(reloadSummary().getStatus()).isEqualTo(SummaryStatus.HOLD);
    }

    @Test
    void 보류함에서_제거는_같은_묶음을_요약하는_중이어도_할_수_있다() {
        // 보류된 판은 워커가 덮어쓰지 않는다 — 새 일지는 새 판이 된다. 제거와 부딪힐 일이 없다.
        service.decide(TEACHER, summary.getId(), Gate1Action.HOLD, null, versionOf(summary.getId()));
        em.flush();
        newEntrySummarizingInSameGroup();

        Gate1DecisionResponse response = service.decide(TEACHER, summary.getId(), Gate1Action.REMOVE, null,
                versionOf(summary.getId()));

        assertThat(response.status()).isEqualTo("REMOVED");
    }

    @Test
    void 최신_판이_아닌_요약은_같은_묶음을_요약하는_중이어도_결정할_수_있다() {
        // 1판을 보류한 사이 2판이 생겼고 1판을 보류 해제했다. 워커는 최신 판(2판)만 덮어쓰므로 1판은 바뀌지 않는다.
        em.persistAndFlush(SummaryResult.of(CHILD, DATE, ORG, 2, "2판", "[]", "[]", "[]", "{}"));
        newEntrySummarizingInSameGroup();

        Gate1DecisionResponse response = service.decide(TEACHER, summary.getId(), Gate1Action.APPROVE, null,
                versionOf(summary.getId()));

        assertThat(response.status()).isEqualTo("APPROVED");
    }

    @Test
    void 요약_글이_그대로여도_요약에_든_일지가_늘었으면_바뀐_것으로_본다() {
        // 워커가 새 일지까지 묶어 다시 요약했는데 AI 가 같은 글을 돌려줬다. 요약 행은 그대로라 수정 시각도 그대로다.
        // 교사는 새 일지를 본 적이 없으니, 받으면 그 일지가 승인과 함께 끝난다.
        String seen = versionOf(summary.getId());
        entryIn(em.find(SummaryResult.class, summary.getId()));
        em.flush();
        em.clear();

        assertThatThrownBy(() -> service.decide(TEACHER, summary.getId(), Gate1Action.APPROVE, null, seen))
                .isInstanceOf(SummaryException.class)
                .extracting(e -> ((SummaryException) e).getErrorCode())
                .isEqualTo(SummaryErrorCode.CHANGED);
    }
}

