package com.itda.backend.service.matching;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.itda.backend.dto.request.MatchingAgentRequest;
import com.itda.backend.exception.AiAgentUnavailableException;

import lombok.extern.slf4j.Slf4j;

/**
 * 대기(PENDING) 일지를 모아 매칭 에이전트에 보내고 결과를 남긴다.
 *
 * <p>한 건씩 순서대로 처리한다 — AI 컨테이너 메모리(512MB) 때문에 동시 호출을 8개 이하로 묶어야 한다.
 * 스케줄러 스레드가 워커당 하나(application.yml {@code spring.task.scheduling.pool.size})이고 같은 워커는
 * 겹쳐 돌지 않으므로, 매칭·검증·요약을 합쳐도 동시 호출은 워커 수를 넘지 않는다.
 *
 * <p>실패는 그 일지 한 건만 FAILED 로 남긴다. 다만 AI 가 꺼져 있을 때 일지를 계속 집으면 대기 중인 일지가
 * 전부 FAILED 가 되므로, 집기 전에 AI 상태를 보고, 재시도까지 응답이 없으면 나머지를 대기로 돌려놓고 멈춘다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.matching.worker", name = "enabled", havingValue = "true")
public class MatchingWorker {

    private final MatchingService matchingService;
    private final MatchingAgentClient client;
    private final MatchingResultRecorder recorder;
    private final int batchSize;

    /**
     * 앱이 켜진 뒤 첫 실행인지. 멈춘 일지 되돌리기를 ApplicationReadyEvent 가 아니라 여기서 하는 이유는,
     * 스케줄러가 그 이벤트보다 먼저 시작해서 이미 집어 간(처리 중인) 일지까지 되돌릴 수 있기 때문이다.
     * 같은 스레드에서 되돌린 다음에 집으면 두 작업이 겹치지 않는다.
     */
    private boolean released;

    /** AI 가 꺼져 있다는 로그를 상태가 바뀔 때만 남기려고 둔다 (5초마다 같은 경고가 쌓이지 않게). */
    private boolean agentDown;

    public MatchingWorker(
            MatchingService matchingService,
            MatchingAgentClient client,
            MatchingResultRecorder recorder,
            MatchingProperties properties) {
        this.matchingService = matchingService;
        this.client = client;
        this.recorder = recorder;
        this.batchSize = properties.worker().batchSize();
    }

    @Scheduled(fixedDelayString = "${app.matching.worker.delay-ms}")
    public void run() {
        if (!released) {
            int count = matchingService.releaseStuck();
            if (count > 0) {
                log.info("released {} journal entries stuck in MATCHING", count);
            }
            released = true;
        }
        if (!agentAvailable()) {
            return;
        }

        List<Long> claimed = matchingService.claimPending(batchSize);
        for (int i = 0; i < claimed.size(); i++) {
            Outcome outcome = process(claimed.get(i));
            if (outcome == Outcome.AGENT_DOWN) {
                matchingService.release(claimed.subList(i + 1, claimed.size()));
                return;
            }
            if (outcome == Outcome.SHUTTING_DOWN) {
                // 남은 일지는 MATCHING 으로 둔다. 다음에 켜질 때 releaseStuck 이 되돌린다.
                return;
            }
        }
    }

    private boolean agentAvailable() {
        boolean available = client.isAvailable();
        if (available == agentDown) {
            if (available) {
                log.info("matching agent is back");
            } else {
                log.warn("matching agent is not responding, skipping until it recovers");
            }
            agentDown = !available;
        }
        return available;
    }

    private Outcome process(Long journalEntryId) {
        try {
            MatchingAgentRequest request = matchingService.prepareRequest(journalEntryId);
            MatchingAgentReply reply = client.match(request);
            recorder.record(journalEntryId, reply);
            return Outcome.DONE;
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("stopping matching worker during shutdown journalEntryId={}", journalEntryId);
                return Outcome.SHUTTING_DOWN;
            }
            log.warn("matching failed journalEntryId={}", journalEntryId, e);
            recordFailure(journalEntryId);
            return e instanceof AiAgentUnavailableException ? Outcome.AGENT_DOWN : Outcome.DONE;
        }
    }

    private void recordFailure(Long journalEntryId) {
        try {
            recorder.recordFailure(journalEntryId);
        } catch (RuntimeException recordError) {
            log.error("could not record matching failure journalEntryId={}", journalEntryId, recordError);
        }
    }

    private enum Outcome {
        DONE,
        AGENT_DOWN,
        SHUTTING_DOWN
    }
}
