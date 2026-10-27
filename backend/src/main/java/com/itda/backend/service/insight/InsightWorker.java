package com.itda.backend.service.insight;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.UnexpectedRollbackException;

import com.itda.backend.domain.insight.InsightRun;
import com.itda.backend.exception.agent.AiAgentUnavailableException;
import com.itda.backend.repository.summary.ChildContextRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 승인된 요약을 아동 × 기간으로 인사이트 에이전트에 보내고 결과를 남긴다.
 *
 * <p>구조는 {@link com.itda.backend.service.summary.SummaryWorker} 와 같고 처리 단위만 묶음이 아니라
 * <b>아동 × 기간</b>이다. 집기·중복 방지는 {@code insight_run} 의 UNIQUE 가 한다 (DB 스키마 §9.5) —
 * 앞의 워커들처럼 입력 행에 상태를 남길 수 없기 때문이다. {@code child_context} 는 승인 이력이라 고치지 않는다.
 *
 * <p>재시도까지 응답이 없으면 <b>지금 아이를 포함해 멈춘다.</b> AI {@code /health} 는 LLM 이 살아 있는지까지
 * 보지 않아서, LLM 장애가 이어지면 아이마다 실패로 끝나기 때문이다 — 요약 워커와 같은 이유다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.insight.worker", name = "enabled", havingValue = "true")
public class InsightWorker {

    private final InsightService insightService;
    private final InsightAgentClient client;
    private final InsightResultRecorder recorder;
    private final ChildContextRepository childContextRepository;
    private final int batchSize;
    private final int periodDays;
    private final ZoneId zone;
    private final int lookbackDays;
    private final boolean includeCurrentWeek;

    /** 앱이 켜진 뒤 첫 실행인지. 되돌리기를 여기서 하는 이유는 {@code SummaryWorker} 와 같다 (집기와 겹치지 않게). */
    private boolean released;
    /** AI 가 꺼져 있다는 로그를 상태가 바뀔 때만 남기려고 둔다 (틱마다 같은 경고가 쌓이지 않게). */
    private boolean agentDown;

    public InsightWorker(InsightService insightService, InsightAgentClient client,
            InsightResultRecorder recorder, ChildContextRepository childContextRepository,
            InsightProperties properties) {
        this.insightService = insightService;
        this.client = client;
        this.recorder = recorder;
        this.childContextRepository = childContextRepository;
        this.batchSize = properties.worker().batchSize();
        this.periodDays = properties.periodDays();
        this.zone = properties.zone();
        this.lookbackDays = properties.lookbackDays();
        this.includeCurrentWeek = properties.includeCurrentWeek();
    }

    @Scheduled(fixedDelayString = "${app.insight.worker.delay-ms}")
    public void run() {
        if (!released) {
            int count = recorder.releaseStuck();
            if (count > 0) {
                log.info("released {} insight runs stuck in RUNNING", count);
            }
            released = true;
        }
        if (!agentAvailable()) {
            return;
        }

        int processed = 0;
        for (Period period : candidates()) {
            if (processed >= batchSize) {
                return;
            }
            Long childId = period.childId();
            LocalDate periodFrom = period.from();
            LocalDate periodTo = period.to();
            if (!recorder.needsRun(childId, periodFrom, periodTo)) {
                continue;
            }

            // 재료를 먼저 본다. 없으면 집지 않는다 — 집어서 DONE 으로 남기면, 그 뒤에 교사가 승인하거나
            // 동의가 들어와도 그 기간을 다시 보지 않는다. "낼 것이 없었다" 와 "아직 안 물어봤다" 는 다르다.
            Optional<PreparedInsight> prepared = insightService.prepare(childId, periodFrom, periodTo);
            if (prepared.isEmpty()) {
                continue;
            }

            Optional<InsightRun> claimed = claim(childId, periodFrom, periodTo);
            if (claimed.isEmpty()) {
                continue;
            }
            processed++;
            if (!process(claimed.get(), prepared.get())) {
                return;
            }
        }
    }

    /**
     * 이번 차례에 볼 (아이, 기간) 후보. <b>최근에 Gate 1 을 통과한 요약</b>이 속한 주를 고른다 (#239).
     *
     * <p>관찰 날짜로 "지난주" 만 보면, 밀린 기록을 2주 뒤에 승인했을 때 그 주를 다시 보지 않아 영영 재료가 안
     * 된다. 그래서 승인 시각으로 고르고, 기간은 그 요약의 관찰 날짜가 속한 주로 둔다.
     *
     * <p>기간의 끝은 <b>일요일</b>로 고정한다. "어제까지 7일" 로 잡으면 자정마다 키가 하루씩 밀려서, 같은 아이가
     * 겹치는 창으로 <b>매일</b> 다시 돌고 비슷한 인사이트가 Gate 2 대기 목록에 쌓인다. 집기는 키가 같을 때만
     * 중복을 막으므로 키가 그대로여야 한다.
     *
     * <p>끝나지 않은 이번 주는 기본으로 빼고, 시연에서만 넣는다 ({@code include-current-week}).
     *
     * <p>ponytail: {@code lookback-days} 보다 오래 AI 가 꺼져 있으면 그사이 승인은 놓친다. 그 일이 생기면
     * 마지막으로 본 {@code child_context.id} 를 커서로 남겨 이어 보게 바꾼다.
     */
    private List<Period> candidates() {
        LocalDate thisSunday = LocalDate.now(zone).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        LocalDate lastEnd = includeCurrentWeek ? thisSunday : thisSunday.minusWeeks(1);
        return childContextRepository.findApprovedEntryDaysSince(LocalDateTime.now().minusDays(lookbackDays))
                .stream()
                .map(day -> {
                    LocalDate to = day.entryDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
                    return new Period(day.childId(), to.minusDays(periodDays - 1L), to);
                })
                .filter(period -> !period.to().isAfter(lastEnd))
                .distinct()
                // 최근 주부터 — 늦은 승인으로 옛 주가 쌓여도 지난주가 밀리지 않게 한다.
                .sorted(Comparator.comparing(Period::to).reversed().thenComparing(Period::childId))
                .toList();
    }

    private record Period(Long childId, LocalDate from, LocalDate to) {
    }

    /**
     * 집기. 경합에 지면 {@link Optional#empty()} 다.
     *
     * <p>제약 위반은 {@code REQUIRES_NEW} 트랜잭션을 롤백 전용으로 만들어, 안에서 잡아도 커밋 시점에
     * 프록시가 {@link UnexpectedRollbackException} 으로 다시 던진다. 그래서 <b>트랜잭션 경계 밖인
     * 여기서</b> 잡는다.
     */
    private Optional<InsightRun> claim(Long childId, LocalDate periodFrom, LocalDate periodTo) {
        try {
            return recorder.claim(childId, periodFrom, periodTo);
        } catch (DataIntegrityViolationException | UnexpectedRollbackException e) {
            log.debug("insight run already claimed childId={} period={}..{}", childId, periodFrom, periodTo);
            return Optional.empty();
        }
    }

    /** @return 계속 진행해도 되면 {@code true}, AI 가 응답하지 않아 이번 차례를 멈춰야 하면 {@code false} */
    private boolean process(InsightRun run, PreparedInsight prepared) {
        try {
            InsightAgentReply reply = client.analyze(prepared.request());
            recorder.record(run, reply.response(), reply.rawJson(), prepared.lastContextId(),
                    prepared.request().claims().size());
            return true;
        } catch (AiAgentUnavailableException e) {
            // 재시도까지 응답이 없었다. 이 아이는 FAILED 로 두고(다음 차례에 revision + 1 로 다시 집는다)
            // 남은 아이는 건드리지 않는다. agentDown 은 건드리지 않는다 — 그건 /health 가 본 상태다.
            log.warn("insight agent unavailable, stopping this turn childId={} period={}..{}",
                    run.getChildId(), run.getPeriodFrom(), run.getPeriodTo(), e);
            recorder.recordFailure(run);
            return false;
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                // 앱이 내려가는 중이다. 실패로 남기면 멀쩡한 실행이 FAILED 로 기록된다.
                // RUNNING 으로 두면 다음에 켜질 때 releaseStuck 이 되돌린다.
                log.info("shutting down, insight run left for the next start childId={}", run.getChildId());
                return false;
            }
            // 4xx·응답 해석 실패. 다시 보내도 같으므로 이 아이만 실패로 남기고 다음으로 간다.
            log.warn("insight call failed, recorded as failure childId={} period={}..{}",
                    run.getChildId(), run.getPeriodFrom(), run.getPeriodTo(), e);
            recorder.recordFailure(run);
            return true;
        }
    }

    private boolean agentAvailable() {
        boolean available = client.isAvailable();
        if (!available && !agentDown) {
            log.warn("insight agent is down, skipping turns until it is back");
            agentDown = true;
        } else if (available && agentDown) {
            log.info("insight agent is back");
            agentDown = false;
        }
        return available;
    }
}
