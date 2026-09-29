package com.itda.backend.service.matching;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.itda.backend.dto.request.MatchingAgentRequest;

import lombok.extern.slf4j.Slf4j;

/**
 * 대기(PENDING) 일지를 모아 매칭 에이전트에 보내고 결과를 남긴다.
 *
 * <p>한 건씩 순서대로 처리한다 — AI 컨테이너 메모리(512MB) 때문에 동시 호출을 8개 이하로 묶어야 하고,
 * 한 스레드로 돌면 그 조건이 저절로 지켜진다. 실패는 그 일지 한 건만 FAILED 로 남기고 다음 건으로 넘어간다.
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
        for (Long journalEntryId : matchingService.claimPending(batchSize)) {
            process(journalEntryId);
        }
    }

    private void process(Long journalEntryId) {
        try {
            MatchingAgentRequest request = matchingService.prepareRequest(journalEntryId);
            MatchingAgentReply reply = client.match(request);
            recorder.record(journalEntryId, reply);
        } catch (RuntimeException e) {
            log.warn("matching failed journalEntryId={}", journalEntryId, e);
            try {
                recorder.recordFailure(journalEntryId);
            } catch (RuntimeException recordError) {
                log.error("could not record matching failure journalEntryId={}", journalEntryId, recordError);
            }
        }
    }
}
