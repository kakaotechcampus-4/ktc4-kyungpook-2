package com.itda.backend.service.validation;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.itda.backend.exception.AiAgentUnavailableException;

import lombok.extern.slf4j.Slf4j;

/**
 * 매칭이 확정된(MATCHED) 일지를 모아 검증 에이전트에 보내고 결과를 남긴다. 판정에 따라 PASS·REVIEW 는
 * VALIDATED(요약 대기), BLOCK 은 VALIDATION_BLOCKED(수정 요청 큐), 호출 실패는 FAILED 가 된다.
 *
 * <p>구조는 {@link com.itda.backend.service.matching.MatchingWorker} 와 같다. 한 건씩 순서대로 처리하고,
 * 실패는 그 일지 한 건만 FAILED 로 남긴다. 집기 전에 AI 상태를 보고, 재시도까지 응답이 없으면 나머지를
 * 검증 대기로 돌려놓고 멈춘다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.validation.worker", name = "enabled", havingValue = "true")
public class ValidationWorker {

    private final ValidationService validationService;
    private final ValidationAgentClient client;
    private final ValidationResultRecorder recorder;
    private final int batchSize;

    /** 앱이 켜진 뒤 첫 실행인지. 되돌리기를 여기서 하는 이유는 MatchingWorker 와 같다 (집기와 겹치지 않게). */
    private boolean released;

    /** AI 가 꺼져 있다는 로그를 상태가 바뀔 때만 남기려고 둔다 (5초마다 같은 경고가 쌓이지 않게). */
    private boolean agentDown;

    public ValidationWorker(
            ValidationService validationService,
            ValidationAgentClient client,
            ValidationResultRecorder recorder,
            ValidationProperties properties) {
        this.validationService = validationService;
        this.client = client;
        this.recorder = recorder;
        this.batchSize = properties.worker().batchSize();
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
        if (!agentAvailable()) {
            return;
        }

        List<Long> claimed = validationService.claimMatched(batchSize);
        for (int i = 0; i < claimed.size(); i++) {
            Outcome outcome = process(claimed.get(i));
            if (outcome == Outcome.AGENT_DOWN) {
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
            log.warn("validation failed journalEntryId={}", journalEntryId, e);
            recordFailure(journalEntryId);
            return e instanceof AiAgentUnavailableException ? Outcome.AGENT_DOWN : Outcome.DONE;
        }
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
