package com.itda.backend.service.validation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.itda.backend.exception.agent.AiAgentUnavailableException;
import com.itda.backend.service.agent.AgentDownProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * 매칭이 확정된(MATCHED) 일지를 모아 검증 에이전트에 보내고 결과를 남긴다. 판정에 따라 PASS·REVIEW 는
 * VALIDATED(요약 대기), BLOCK 은 VALIDATION_BLOCKED(수정 요청 큐), 호출 실패는 FAILED 가 된다.
 *
 * <p>구조는 {@link com.itda.backend.service.matching.MatchingWorker} 와 같다. 한 건씩 순서대로 처리하고,
 * 실패는 그 일지 한 건만 FAILED 로 남긴다. 집기 전에 AI 상태를 보고, 재시도까지 응답이 없으면(AI 다운) 처리 중이던
 * 일지까지 검증 대기로 돌려놓고 멈춘다. LLM 만 죽어 있으면 {@code /health} 는 통과하고 AI 가 503 을 주는데, 예전에는
 * 이때 차례마다 일지가 한 건씩 FAILED 로 빠졌다. 같은 일지가 상한({@code app.ai.agent-down.max-releases})을 넘겨
 * 다운을 만나면 그 일지만 FAILED 로 남긴다 (#153).
 *
 * <p>AI 다운을 만나면 {@code app.ai.agent-down.pause} 동안 AI 상태도 보지 않고 쉰다. 쉬지 않으면 다음 차례에 바로
 * 다시 불러 짧은 장애에도 상한을 채운다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.validation.worker", name = "enabled", havingValue = "true")
public class ValidationWorker {

    private final ValidationService validationService;
    private final ValidationAgentClient client;
    private final ValidationResultRecorder recorder;
    private final int batchSize;
    private final int maxReleases;
    private final Duration pause;
    private final Clock clock;

    /** 앱이 켜진 뒤 첫 실행인지. 되돌리기를 여기서 하는 이유는 MatchingWorker 와 같다 (집기와 겹치지 않게). */
    private boolean released;

    /** AI 가 꺼져 있다는 로그를 상태가 바뀔 때만 남기려고 둔다 (5초마다 같은 경고가 쌓이지 않게). */
    private boolean agentDown;

    /** AI 다운을 만난 뒤 이 시각까지는 AI 상태도 보지 않고 쉰다. 같은 스레드에서만 읽고 쓴다. */
    private Instant pausedUntil = Instant.MIN;

    public ValidationWorker(
            ValidationService validationService,
            ValidationAgentClient client,
            ValidationResultRecorder recorder,
            ValidationProperties properties,
            AgentDownProperties agentDown,
            Clock clock) {
        this.validationService = validationService;
        this.client = client;
        this.recorder = recorder;
        this.batchSize = properties.worker().batchSize();
        this.maxReleases = agentDown.maxReleases();
        this.pause = agentDown.pause();
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.validation.worker.delay-ms}")
    public void run() {
        if (!released) {
            int count = validationService.releaseStuck();
            if (count > 0) {
                log.info("released {} journal entries stuck in VALIDATING", count);
            }
            released = true;
        }
        if (clock.instant().isBefore(pausedUntil) || !agentAvailable()) {
            return;
        }

        List<Long> claimed = validationService.claimMatched(batchSize);
        for (int i = 0; i < claimed.size(); i++) {
            Outcome outcome = process(claimed.get(i));
            if (outcome == Outcome.AGENT_DOWN) {
                // 쉬기를 먼저 건다. 되돌리기가 실패해 예외가 나가도 다음 차례에 바로 다시 부르지 않게.
                pausedUntil = clock.instant().plus(pause);
                log.info("validation agent down, pausing for {}", pause);
                validationService.release(claimed.subList(i + 1, claimed.size()));
                return;
            }
            if (outcome == Outcome.SHUTTING_DOWN) {
                // 남은 일지는 VALIDATING 으로 둔다. 다음에 켜질 때 releaseStuck 이 되돌린다.
                return;
            }
        }
    }

    private boolean agentAvailable() {
        boolean available = client.isAvailable();
        if (available == agentDown) {
            if (available) {
                log.info("validation agent is back");
            } else {
                log.warn("validation agent is not responding, skipping until it recovers");
            }
            agentDown = !available;
        }
        return available;
    }

    private Outcome process(Long journalEntryId) {
        try {
            ValidationTarget target = validationService.prepareRequest(journalEntryId);
            ValidationAgentReply reply = client.validate(target.request());
            recorder.record(target, reply);
            return Outcome.DONE;
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("stopping validation worker during shutdown journalEntryId={}", journalEntryId);
                return Outcome.SHUTTING_DOWN;
            }
            if (e instanceof AiAgentUnavailableException) {
                log.warn("validation agent unavailable journalEntryId={}", journalEntryId, e);
                return releaseOrFail(journalEntryId);
            }
            log.warn("validation failed journalEntryId={}", journalEntryId, e);
            recordFailure(journalEntryId);
            return Outcome.DONE;
        }
    }

    /**
     * AI 다운은 일지 잘못이 아니라서 실패로 남기지 않고 검증 대기로 되돌린다. 이미 상한만큼 되돌린 일지면 실패로 남긴다 —
     * AI 가 이 일지만 계속 거절하는 경우를 다운과 구별할 수 없어서, 그런 일지가 대기열 맨 앞을 막지 않게 한다.
     *
     * @return 되돌렸으면 {@link Outcome#AGENT_DOWN} (나머지도 되돌리고 쉰다). 실패로 남겼으면 {@link Outcome#DONE} —
     *         그 일지는 빠졌으니 다음 일지를 계속한다. 정말 다운이면 다음 일지에서 다시 다운을 만나 그때 멈춘다
     */
    private Outcome releaseOrFail(Long journalEntryId) {
        try {
            if (validationService.releaseAfterAgentDown(journalEntryId, maxReleases)) {
                return Outcome.AGENT_DOWN;
            }
        } catch (RuntimeException releaseError) {
            // 일지는 처리 중 상태로 남는다. 다음에 켜질 때 releaseStuck 이 되돌린다.
            log.error("could not release validation after agent down journalEntryId={}", journalEntryId, releaseError);
            return Outcome.AGENT_DOWN;
        }
        log.warn("validation agent down {} times in a row, recording as failure journalEntryId={}",
                maxReleases + 1, journalEntryId);
        recordFailure(journalEntryId);
        return Outcome.DONE;
    }

    private void recordFailure(Long journalEntryId) {
        try {
            recorder.recordFailure(journalEntryId);
        } catch (RuntimeException recordError) {
            log.error("could not record validation failure journalEntryId={}", journalEntryId, recordError);
        }
    }

    private enum Outcome {
        DONE,
        AGENT_DOWN,
        SHUTTING_DOWN
    }
}
