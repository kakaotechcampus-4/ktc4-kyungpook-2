package com.itda.backend.service.summary;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.itda.backend.exception.AiAgentUnavailableException;

import lombok.extern.slf4j.Slf4j;

/**
 * 검증을 통과한(VALIDATED) 일지를 아동 × 날짜 × 기관 묶음으로 요약 에이전트에 보내고 결과를 남긴다. 성공하면
 * 묶음의 일지가 GATE1_PENDING(교사 1차 검토 대기)이 되고, 호출 실패는 FAILED 가 된다.
 *
 * <p>구조는 {@link com.itda.backend.service.validation.ValidationWorker} 와 같고 처리 단위만 일지가 아니라 묶음이다.
 * 한 묶음씩 순서대로 처리하고, 실패는 그 묶음만 실패로 남긴다. 집기 전에 AI 상태를 보고, 재시도까지 응답이
 * 없으면 나머지 묶음을 되돌려놓고 멈춘다. 언제 묶음이 준비되는지는 {@link SummaryService#claimReadyGroups} 가 정한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.summary.worker", name = "enabled", havingValue = "true")
public class SummaryWorker {

    private final SummaryService summaryService;
    private final SummaryAgentClient client;
    private final SummaryResultRecorder recorder;
    private final int batchSize;

    /** 앱이 켜진 뒤 첫 실행인지. 되돌리기를 여기서 하는 이유는 MatchingWorker 와 같다 (집기와 겹치지 않게). */
    private boolean released;

    /** AI 가 꺼져 있다는 로그를 상태가 바뀔 때만 남기려고 둔다 (5초마다 같은 경고가 쌓이지 않게). */
    private boolean agentDown;

    public SummaryWorker(
            SummaryService summaryService,
            SummaryAgentClient client,
            SummaryResultRecorder recorder,
            SummaryProperties properties) {
        this.summaryService = summaryService;
        this.client = client;
        this.recorder = recorder;
        this.batchSize = properties.worker().batchSize();
    }

    @Scheduled(fixedDelayString = "${app.summary.worker.delay-ms}")
    public void run() {
        if (!released) {
            int count = summaryService.releaseStuck();
            if (count > 0) {
                log.info("released {} journal entries stuck in SUMMARIZING", count);
            }
            released = true;
        }
        if (!agentAvailable()) {
            return;
        }

        List<ClaimedSummaryGroup> claimed = summaryService.claimReadyGroups(batchSize);
        for (int i = 0; i < claimed.size(); i++) {
            Outcome outcome = process(claimed.get(i));
            if (outcome == Outcome.AGENT_DOWN) {
                summaryService.release(claimed.subList(i + 1, claimed.size()));
                return;
            }
            if (outcome == Outcome.SHUTTING_DOWN) {
                // 남은 묶음은 SUMMARIZING 으로 둔다. 다음에 켜질 때 releaseStuck 이 되돌린다.
                return;
            }
        }
    }

    private boolean agentAvailable() {
        boolean available = client.isAvailable();
        if (available == agentDown) {
            if (available) {
                log.info("summary agent is back");
            } else {
                log.warn("summary agent is not responding, skipping until it recovers");
            }
            agentDown = !available;
        }
        return available;
    }

    private Outcome process(ClaimedSummaryGroup claimed) {
        try {
            SummaryAgentReply reply = client.summarize(summaryService.prepareRequest(claimed));
            recorder.record(claimed, reply);
            return Outcome.DONE;
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("stopping summary worker during shutdown group={}", claimed.group());
                return Outcome.SHUTTING_DOWN;
            }
            log.warn("summary failed group={} journalEntryIds={}", claimed.group(), claimed.journalEntryIds(), e);
            recordFailure(claimed);
            return e instanceof AiAgentUnavailableException ? Outcome.AGENT_DOWN : Outcome.DONE;
        }
    }

    private void recordFailure(ClaimedSummaryGroup claimed) {
        try {
            recorder.recordFailure(claimed);
        } catch (RuntimeException recordError) {
            log.error("could not record summary failure group={}", claimed.group(), recordError);
        }
    }

    private enum Outcome {
        DONE,
        AGENT_DOWN,
        SHUTTING_DOWN
    }
}
