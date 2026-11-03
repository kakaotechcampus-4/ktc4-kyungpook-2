package com.itda.backend.service.insight;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.insight.InsightRun;
import com.itda.backend.domain.summary.ChildContext;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.domain.insight.InsightRunStatus;
import com.itda.backend.domain.insight.InsightStatus;
import com.itda.backend.dto.response.insight.InsightAgentResponse;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.insight.InsightRunRepository;
import com.itda.backend.repository.summary.ChildContextRepository;
import com.itda.backend.repository.summary.SummaryResultRepository;

/**
 * 집기·저장. {@code claim} 이 {@code REQUIRES_NEW} 라 테스트가 트랜잭션을 들고 있으면 안 되므로
 * {@code @DataJpaTest} 가 아니라 실제 컨텍스트로 띄운다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InsightResultRecorderTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 7);
    private static final Long CHILD = 8L;

    @Autowired
    private InsightResultRecorder recorder;
    @Autowired
    private InsightRunRepository insightRunRepository;
    @Autowired
    private InsightResultRepository insightResultRepository;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SummaryResultRepository summaryResultRepository;
    @Autowired
    private ChildContextRepository childContextRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        insightResultRepository.deleteAll();
        insightRunRepository.deleteAll();
        childContextRepository.deleteAll();
        summaryResultRepository.deleteAll();
    }

    /**
     * 승인된 요약 한 건을 만든다. <b>눈금 비교가 살아 있으려면 재료가 실제로 있어야 한다</b> —
     * 없으면 {@code findMaxIdInPeriod} 가 늘 {@code null} 이라 비교 분기에 닿지 못한다.
     *
     * @return 그 승인본의 {@code child_context.id} (= 그 시점의 눈금)
     */
    private Long approve(LocalDate entryDate) {
        SummaryResult summary = summaryResultRepository.saveAndFlush(SummaryResult.of(
                CHILD, entryDate, 3L, 1, "요약 본문", "[]", "[]", "[]", "{}"));
        return childContextRepository
                .saveAndFlush(ChildContext.of(CHILD, summary.getId(), "승인된 본문")).getId();
    }

    /**
     * 실행 기록의 시각을 {@code elapsed} 만큼 앞으로 당긴다 — 그만큼 시간이 흐른 것과 같다. 실패한 아이는
     * 기다렸다가 다시 집으므로(#266, 기본 1m,5m,30m) 바로 다시 집는 테스트는 이걸 먼저 부른다.
     */
    private void elapse(Duration elapsed) {
        for (InsightRun run : insightRunRepository.findAll()) {
            jdbcTemplate.update("UPDATE insight_run SET updated_at = ? WHERE id = ?",
                    run.getUpdatedAt().minus(elapsed), run.getId());
        }
    }

    private void waitOut() {
        elapse(Duration.ofDays(1));
    }

    private InsightAgentResponse response(String json) throws Exception {
        return objectMapper.readValue(json, InsightAgentResponse.class);
    }

    private InsightAgentResponse oneInsight() throws Exception {
        return response("""
                {"child_id": 8, "period_from": "2026-10-01", "period_to": "2026-10-07",
                 "insights": [
                   {"situation": "점심 줄에서 기다릴 때",
                    "behavior": "기관에 따라 반응이 달랐다",
                    "content": "학교에서는 차례를 기다렸고, 센터에서는 끼어들었다.",
                    "supports": [{"action": "손을 잡고 셈", "result": "진정됨", "claim_ids": ["31-0"]}],
                    "claim_ids": ["31-0", "34-1"],
                    "source_entry_ids": [1041, 1042],
                    "source_institution_ids": [3, 5],
                    "cross_institution": true}],
                 "llm_called": true}
                """);
    }

    @Test
    void 처음_집으면_1판이다() {
        InsightRun run = recorder.claim(CHILD, FROM, TO).orElseThrow();

        assertThat(run.getRevision()).isEqualTo(1);
        assertThat(run.getStatus()).isEqualTo(InsightRunStatus.RUNNING);
    }

    /** 이 테이블을 둔 이유다 — 0건으로 끝난 기간을 다시 집으면 LLM 을 또 부른다. */
    @Test
    void 인사이트_0건으로_끝난_기간은_다시_집지_않는다() throws Exception {
        InsightRun run = recorder.claim(CHILD, FROM, TO).orElseThrow();
        recorder.record(run, response("""
                {"child_id": 8, "insights": [], "llm_called": true}
                """), "{}", 41L, 1);

        assertThat(recorder.claim(CHILD, FROM, TO)).isEmpty();
        assertThat(insightRunRepository.findAll()).singleElement()
                .satisfies(saved -> {
                    assertThat(saved.getStatus()).isEqualTo(InsightRunStatus.DONE);
                    assertThat(saved.getInsightCount()).isZero();
                });
    }

    @Test
    void 결과가_있는_기간도_다시_집지_않는다() throws Exception {
        InsightRun run = recorder.claim(CHILD, FROM, TO).orElseThrow();
        recorder.record(run, oneInsight(), "{\"raw\": true}", 41L, 1);

        assertThat(recorder.claim(CHILD, FROM, TO)).isEmpty();
    }

    /** 실패한 판은 고치지 않고 다음 판으로 다시 집는다 (§3.2). */
    @Test
    void 실패한_기간은_다음_판으로_다시_집는다() {
        InsightRun first = recorder.claim(CHILD, FROM, TO).orElseThrow();
        recorder.recordFailure(first, "llm_unavailable");
        waitOut();

        InsightRun second = recorder.claim(CHILD, FROM, TO).orElseThrow();

        assertThat(second.getRevision()).isEqualTo(2);
        assertThat(insightRunRepository.findAll()).hasSize(2);
    }

    @Test
    void 돌고_있는_기간은_집지_않는다() {
        recorder.claim(CHILD, FROM, TO).orElseThrow();

        assertThat(recorder.claim(CHILD, FROM, TO)).isEmpty();
    }

    @Test
    void 기간이_다르면_따로_집는다() {
        recorder.claim(CHILD, FROM, TO).orElseThrow();

        assertThat(recorder.claim(CHILD, FROM.minusDays(7), TO.minusDays(7))).isPresent();
    }

    @Test
    void 응답을_인사이트_행으로_남긴다() throws Exception {
        InsightRun run = recorder.claim(CHILD, FROM, TO).orElseThrow();

        recorder.record(run, oneInsight(), "{\"raw\": true}", 41L, 1);

        List<InsightResult> saved = insightResultRepository.findByStatusOrderByIdDesc(InsightStatus.GATE2_PENDING);
        assertThat(saved).singleElement().satisfies(result -> {
            assertThat(result.getChildId()).isEqualTo(CHILD);
            assertThat(result.getPeriodFrom()).isEqualTo(FROM);
            assertThat(result.getRevision()).isEqualTo(1);
            assertThat(result.getContent()).isEqualTo("학교에서는 차례를 기다렸고, 센터에서는 끼어들었다.");
            assertThat(result.isCrossInstitution()).isTrue();
            // AI 가 보낸 JSON 조각을 그대로 담는다 — summary_result.claims 와 같은 방식
            assertThat(result.getClaimIds()).isEqualTo("[\"31-0\",\"34-1\"]");
            assertThat(result.getSourceEntryIds()).isEqualTo("[1041,1042]");
            assertThat(result.getRawResponse()).isEqualTo("{\"raw\": true}");
        });
        assertThat(insightRunRepository.findAll()).singleElement()
                .satisfies(saved2 -> assertThat(saved2.getInsightCount()).isEqualTo(1));
    }

    /** 교사가 읽고 판단할 글이 없는 건이다. 한 건 때문에 나머지를 버리지는 않는다. */
    @Test
    void 본문이_빈_인사이트는_버리고_나머지만_남긴다() throws Exception {
        InsightRun run = recorder.claim(CHILD, FROM, TO).orElseThrow();

        recorder.record(run, response("""
                {"child_id": 8, "insights": [
                   {"situation": "상황", "behavior": "반응", "content": "  "},
                   {"situation": "상황2", "behavior": "반응2", "content": "제대로 된 본문"}],
                 "llm_called": true}
                """), "{}", 41L, 1);

        assertThat(insightResultRepository.findAll()).singleElement()
                .satisfies(result -> assertThat(result.getContent()).isEqualTo("제대로 된 본문"));
        assertThat(insightRunRepository.findAll()).singleElement()
                .satisfies(saved -> assertThat(saved.getInsightCount()).isEqualTo(1));
    }

    @Test
    void 저장한_인사이트는_Gate2_대기로_들어간다() throws Exception {
        InsightRun run = recorder.claim(CHILD, FROM, TO).orElseThrow();

        recorder.record(run, oneInsight(), "{}", 41L, 1);

        assertThat(insightResultRepository.findAll()).singleElement()
                .satisfies(result -> assertThat(result.getStatus()).isEqualTo(InsightStatus.GATE2_PENDING));
    }

    @Test
    void 실패하면_건수_없이_FAILED로_남는다() {
        InsightRun run = recorder.claim(CHILD, FROM, TO).orElseThrow();

        recorder.recordFailure(run, "llm_unavailable");

        assertThat(insightRunRepository.findAll()).singleElement().satisfies(saved -> {
            assertThat(saved.getStatus()).isEqualTo(InsightRunStatus.FAILED);
            assertThat(saved.getInsightCount()).isNull();
            assertThat(saved.getFailureReason()).isEqualTo("llm_unavailable");
        });
        assertThat(insightResultRepository.findAll()).isEmpty();
    }

    @Test
    void 응답의_insights가_null이어도_끝낸_것으로_남긴다() throws Exception {
        InsightRun run = recorder.claim(CHILD, FROM, TO).orElseThrow();

        recorder.record(run, new InsightAgentResponse(CHILD, null, null, null, false), null, 41L, 1);

        assertThat(insightRunRepository.findAll()).singleElement().satisfies(saved -> {
            assertThat(saved.getStatus()).isEqualTo(InsightRunStatus.DONE);
            assertThat(saved.getInsightCount()).isZero();
        });
    }

    @Test
    void 끝난_기간은_다시_집지_않는다() throws Exception {
        Long mark = approve(FROM.plusDays(1));
        recorder.record(recorder.claim(CHILD, FROM, TO).orElseThrow(), response("""
                {"child_id": 8, "insights": [], "llm_called": true}
                """), "{}", mark, 1);

        assertThat(recorder.needsRun(CHILD, FROM, TO)).isFalse();
    }

    /**
     * 멘토 리뷰(#191) — Gate 1 승인은 틈틈이 일어난다. 끝난 뒤에 같은 기간에 승인이 더 생기면
     * 그 기록도 반영돼야 한다.
     */
    @Test
    void 끝난_뒤_같은_기간에_승인이_더_생기면_다시_집는다() throws Exception {
        Long mark = approve(FROM.plusDays(1));
        recorder.record(recorder.claim(CHILD, FROM, TO).orElseThrow(), response("""
                {"child_id": 8, "insights": [], "llm_called": true}
                """), "{}", mark, 1);
        assertThat(recorder.needsRun(CHILD, FROM, TO)).isFalse();

        approve(FROM.plusDays(3));

        assertThat(recorder.needsRun(CHILD, FROM, TO)).isTrue();
        assertThat(recorder.claim(CHILD, FROM, TO)).get()
                .satisfies(run -> assertThat(run.getRevision()).isEqualTo(2));
    }

    /** 기간 밖 승인은 이 기간의 눈금이 아니다. */
    @Test
    void 기간_밖에_승인이_생겨도_다시_집지_않는다() throws Exception {
        Long mark = approve(FROM.plusDays(1));
        recorder.record(recorder.claim(CHILD, FROM, TO).orElseThrow(), response("""
                {"child_id": 8, "insights": [], "llm_called": true}
                """), "{}", mark, 1);

        approve(TO.plusDays(1));

        assertThat(recorder.needsRun(CHILD, FROM, TO)).isFalse();
    }

    @Test
    void 아직_안_본_기간은_집는다() {
        assertThat(recorder.needsRun(CHILD, FROM, TO)).isTrue();
    }

    /** 실패한 판은 다시 집어야 하므로 "다뤘다" 가 아니다. */
    @Test
    void 실패한_기간은_다시_집는다() {
        recorder.recordFailure(recorder.claim(CHILD, FROM, TO).orElseThrow(), "llm_unavailable");
        waitOut();

        assertThat(recorder.needsRun(CHILD, FROM, TO)).isTrue();
    }

    /**
     * 앱이 죽어 RUNNING 으로 남은 실행. 되돌리지 않으면 집기가 FAILED 만 가져가므로
     * 그 아동·기간은 영영 처리되지 않는다.
     */
    @Test
    void 멈춘_실행을_되돌리면_다시_집을_수_있다() {
        recorder.claim(CHILD, FROM, TO).orElseThrow();
        assertThat(recorder.claim(CHILD, FROM, TO)).isEmpty();

        assertThat(recorder.releaseStuck()).isEqualTo(1);
        assertThat(latest()).get().satisfies(run -> assertThat(run.getFailureReason()).isEqualTo("stuck"));
        waitOut();

        assertThat(recorder.needsRun(CHILD, FROM, TO)).isTrue();
        assertThat(recorder.claim(CHILD, FROM, TO)).get()
                .satisfies(run -> assertThat(run.getRevision()).isEqualTo(2));
    }

    @Test
    void 끝난_실행은_되돌리지_않는다() throws Exception {
        recorder.record(recorder.claim(CHILD, FROM, TO).orElseThrow(), oneInsight(), "{}", 41L, 1);

        assertThat(recorder.releaseStuck()).isZero();
        assertThat(recorder.needsRun(CHILD, FROM, TO)).isFalse();
    }

    private Optional<InsightRun> latest() {
        return insightRunRepository
                .findFirstByChildIdAndPeriodFromAndPeriodToOrderByRevisionDesc(CHILD, FROM, TO);
    }

    @Test
    void 가장_최근_판을_찾는다() {
        recorder.recordFailure(recorder.claim(CHILD, FROM, TO).orElseThrow(), "llm_unavailable");
        waitOut();
        recorder.recordFailure(recorder.claim(CHILD, FROM, TO).orElseThrow(), "llm_unavailable");

        assertThat(latest()).get().satisfies(run -> assertThat(run.getRevision()).isEqualTo(2));
    }

    /**
     * #266 — 바로 다시 집으면 다음 차례에도 이 아이가 맨 앞에 온다. 계속 실패하면 뒤의 아이들이 차례를 못 받는다.
     */
    @Test
    void 실패한_직후에는_다시_집지_않는다() {
        recorder.recordFailure(recorder.claim(CHILD, FROM, TO).orElseThrow(), "llm_unavailable:empty_response");

        assertThat(recorder.needsRun(CHILD, FROM, TO)).isFalse();
        assertThat(recorder.claim(CHILD, FROM, TO)).isEmpty();
    }

    /** 1m → 5m → 30m. 연달아 실패할수록 오래 기다린다. */
    @Test
    void 연달아_실패할수록_오래_기다린다() {
        recorder.recordFailure(recorder.claim(CHILD, FROM, TO).orElseThrow(), "llm_unavailable");
        elapse(Duration.ofMinutes(2));
        assertThat(recorder.needsRun(CHILD, FROM, TO)).isTrue();

        recorder.recordFailure(recorder.claim(CHILD, FROM, TO).orElseThrow(), "llm_unavailable");
        elapse(Duration.ofMinutes(2));
        assertThat(recorder.needsRun(CHILD, FROM, TO)).isFalse();

        elapse(Duration.ofMinutes(4));
        assertThat(recorder.needsRun(CHILD, FROM, TO)).isTrue();
    }

    /** 성공한 판 앞의 실패는 세지 않는다. 재료가 늘어 다시 돈 판이 실패하면 처음 실패처럼 1m 만 기다린다. */
    @Test
    void 성공한_뒤의_실패는_처음부터_센다() throws Exception {
        recorder.recordFailure(recorder.claim(CHILD, FROM, TO).orElseThrow(), "llm_unavailable");
        waitOut();
        recorder.recordFailure(recorder.claim(CHILD, FROM, TO).orElseThrow(), "llm_unavailable");
        waitOut();
        Long mark = approve(FROM.plusDays(1));
        recorder.record(recorder.claim(CHILD, FROM, TO).orElseThrow(), response("""
                {"child_id": 8, "insights": [], "llm_called": true}
                """), "{}", mark, 1);
        approve(FROM.plusDays(2));

        recorder.recordFailure(recorder.claim(CHILD, FROM, TO).orElseThrow(), "llm_unavailable");
        elapse(Duration.ofMinutes(2));

        assertThat(recorder.needsRun(CHILD, FROM, TO)).isTrue();
    }
}
