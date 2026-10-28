package com.itda.backend.service.insight;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.insight.InsightRun;
import com.itda.backend.domain.insight.InsightRunStatus;
import com.itda.backend.dto.response.insight.InsightAgentResponse;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.insight.InsightRunRepository;
import com.itda.backend.repository.summary.ChildContextRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * AI 인사이트 결과를 {@code insight_result} 와 {@code insight_run} 에 쓰는 곳은 여기 하나다.
 *
 * <p><b>한 번 호출이 여러 행이라 트랜잭션으로 묶는다.</b> 중간에 끊기면 반쪽짜리 인사이트가 Gate 2 에
 * 올라가고, 실행 기록은 {@code RUNNING} 으로 남아 다시 집지도 못한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InsightResultRecorder {

    private final InsightResultRepository insightResultRepository;
    private final InsightRunRepository insightRunRepository;
    private final ChildContextRepository childContextRepository;

    /**
     * 그 아이·그 기간을 (다시) 집어야 하는지.
     *
     * <p>집기 전에 먼저 본다 — 재료를 모으는 일이 쿼리 몇 번이라, 볼 필요 없는 아이에게 그걸 돌릴 이유가 없다.
     *
     * <table>
     * <tr><td>실행 기록 없음 · {@code FAILED}</td><td>집는다</td></tr>
     * <tr><td>{@code RUNNING}</td><td>다른 워커가 돌고 있거나 앱이 죽어 남은 것이다. 건너뛴다</td></tr>
     * <tr><td>{@code DONE} 인데 재료가 늘었다</td><td><b>다시 집는다</b> (멘토 리뷰 #191)</td></tr>
     * <tr><td>{@code DONE} 이고 그대로</td><td>건너뛴다</td></tr>
     * </table>
     *
     * <p><b>{@code insightCount} 가 0 이어도 끝난 것이다</b> — 낼 것이 없었다는 뜻이지 못 낸 게 아니다 (§9.5).
     * 그래서 0 건이라는 이유로 다시 집지는 않는다. 다시 집는 기준은 <b>재료가 늘었는가</b> 하나다.
     */
    @Transactional(readOnly = true)
    public boolean needsRun(Long childId, LocalDate periodFrom, LocalDate periodTo) {
        return shouldRun(latestRun(childId, periodFrom, periodTo), childId, periodFrom, periodTo);
    }

    private InsightRun latestRun(Long childId, LocalDate periodFrom, LocalDate periodTo) {
        return insightRunRepository
                .findFirstByChildIdAndPeriodFromAndPeriodToOrderByRevisionDesc(childId, periodFrom, periodTo)
                .orElse(null);
    }

    /**
     * 집어야 하는지 판정한다. {@link #needsRun} 과 {@link #claim} 이 같은 규칙을 쓴다 —
     * 둘이 갈리면 워커가 통과시킨 아이를 집기가 거부해서 조용히 아무 일도 일어나지 않는다.
     */
    private boolean shouldRun(InsightRun latest, Long childId, LocalDate periodFrom, LocalDate periodTo) {
        if (latest == null || latest.getStatus() == InsightRunStatus.FAILED) {
            return true;
        }
        if (latest.getStatus() == InsightRunStatus.RUNNING) {
            return false;
        }
        if (latest.getLastContextId() == null) {
            // 눈금을 모르는 행이다 (이 컬럼이 생기기 전). 한 번 더 도는 쪽이 빠뜨리는 쪽보다 낫다.
            return true;
        }
        Long current = childContextRepository.findMaxIdInPeriod(childId, periodFrom, periodTo);
        return current != null && current > latest.getLastContextId();
    }

    /**
     * 그 아이·그 기간을 집는다. 이미 끝난 기간이면 {@link Optional#empty()} 다.
     *
     * <p><b>집기가 곧 INSERT 다.</b> 워커 둘이 같은 아이를 동시에 집으면 UNIQUE 에 걸린 쪽이 진다 (§9.5).
     * 진 쪽에서는 {@link org.springframework.dao.DataIntegrityViolationException} 이나
     * {@link org.springframework.transaction.UnexpectedRollbackException} 이 올라간다 —
     * <b>여기서 잡지 않는다.</b> 제약 위반은 트랜잭션을 롤백 전용으로 표시하므로, 안에서 삼켜도 커밋 시점에
     * 프록시가 다시 던진다. 잡을 자리는 트랜잭션 경계 <b>밖</b>, 즉 호출하는 워커다.
     *
     * <p>{@code REQUIRES_NEW} 로 떼어 두는 것은 그 롤백이 바깥 트랜잭션까지 더럽히지 않게 하기 위해서다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<InsightRun> claim(Long childId, LocalDate periodFrom, LocalDate periodTo) {
        InsightRun previous = latestRun(childId, periodFrom, periodTo);
        if (!shouldRun(previous, childId, periodFrom, periodTo)) {
            return Optional.empty();
        }

        int revision = previous == null ? 1 : previous.getRevision() + 1;
        return Optional.of(insightRunRepository
                .saveAndFlush(InsightRun.claim(childId, periodFrom, periodTo, revision)));
    }

    /**
     * 앱이 죽어 {@code RUNNING} 으로 남은 실행을 {@code FAILED} 로 되돌린다.
     *
     * <p>집기는 {@code FAILED} 만 다시 가져가므로, 되돌리지 않으면 그 아동·기간은 <b>영영 처리되지 않는다.</b>
     * 워커가 켜진 뒤 첫 차례에 한 번 부른다 — 집기와 겹치지 않게 하려는 것이고,
     * {@code SummaryWorker} 가 {@code releaseStuck} 을 부르는 것과 같은 자리다.
     *
     * @return 되돌린 수
     */
    @Transactional
    public int releaseStuck() {
        List<InsightRun> stuck = insightRunRepository.findByStatus(InsightRunStatus.RUNNING);
        stuck.forEach(InsightRun::fail);
        insightRunRepository.saveAll(stuck);
        return stuck.size();
    }

    /**
     * 인사이트들을 저장하고 실행을 끝냄으로 표시한다.
     *
     * <p><b>{@code insights} 가 비어도 성공이다.</b> 억지로 만든 패턴이 과잉해석이라 "낼 것이 없었다" 가
     * 정답인 경우가 있다 ({@code AI/insight/CRITERIA.md} §3). 그래도 실행 기록은 남겨야 다음 차례에
     * 같은 아이를 다시 부르지 않는다 (§9.5).
     *
     * @param rawJson       AI 응답 원문. {@code insight_result.raw_response} 에 그대로 남긴다
     * @param lastContextId 재료를 모을 때 본 {@code child_context} 중 가장 큰 id. 다음 차례가 이 값과
     *                      견줘 재료가 늘었는지 본다 (§9.5)
     */
    @Transactional
    public void record(InsightRun run, InsightAgentResponse response, String rawJson, Long lastContextId,
            int claimCount) {
        List<InsightAgentResponse.Insight> insights =
                response.insights() == null ? List.of() : response.insights();

        List<InsightResult> saved = new ArrayList<>();
        for (InsightAgentResponse.Insight insight : insights) {
            if (isBlank(insight.situation()) || isBlank(insight.behavior()) || isBlank(insight.content())) {
                // 교사가 읽고 판단할 글이 없는 인사이트다. 한 건 때문에 나머지를 버리지는 않는다.
                log.warn("insight missing required text, dropped childId={} period={}..{}",
                        run.getChildId(), run.getPeriodFrom(), run.getPeriodTo());
                continue;
            }
            saved.add(toEntity(run, insight, rawJson));
        }
        insightResultRepository.saveAll(saved);
        if (saved.isEmpty()) {
            // 0 건은 정상이다 — 억지로 만든 패턴이 과잉해석이다 (§9.5). 다만 <b>왜</b> 0 건인지는 남지 않는다.
            // AI 가 원문 근거(source=AI) claim 이 없는 인사이트를 통째로 버리기 때문에(REQUIRE_SOURCE_CLAIM,
            // #212), 교사가 카드를 많이 고친 주에는 조용히 빈 주가 된다. 교사는 "이번 주엔 패턴이 없었나" 로
            // 읽지 자기가 고쳐서라고는 생각하지 못한다. 데모에서 "왜 안 나오지" 가 나오면 여기부터 본다.
            log.info("insight run produced nothing childId={} period={}..{} claims={}",
                    run.getChildId(), run.getPeriodFrom(), run.getPeriodTo(), claimCount);
        }

        run.finish(saved.size(), lastContextId);
        insightRunRepository.save(run);
    }

    /** 호출이 실패했다. 다음 차례에 {@code revision + 1} 로 다시 집는다. */
    @Transactional
    public void recordFailure(InsightRun run) {
        run.fail();
        insightRunRepository.save(run);
    }

    private InsightResult toEntity(InsightRun run, InsightAgentResponse.Insight insight, String rawJson) {
        LocalDate from = run.getPeriodFrom();
        LocalDate to = run.getPeriodTo();
        return InsightResult.of(run.getChildId(), from, to, run.getRevision(),
                insight.situation(), insight.behavior(), insight.content(),
                json(insight.supports()), json(insight.claimIds()),
                json(insight.sourceEntryIds()), json(insight.sourceInstitutionIds()),
                Boolean.TRUE.equals(insight.crossInstitution()), rawJson);
    }

    /** AI 가 보낸 JSON 조각을 그대로 담는다 — {@code summary_result.claims} 와 같은 방식이다. */
    private String json(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
