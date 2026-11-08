package com.itda.backend.service.summary;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.itda.backend.domain.record.FailureReason;
import com.itda.backend.dto.request.summary.SummaryAgentRequest;
import com.itda.backend.exception.agent.AiAgentUnavailableException;
import com.itda.backend.exception.summary.SummaryNoGroundedClaimsException;
import com.itda.backend.service.agent.AgentFailureReasons;
import com.itda.backend.service.agent.AiAvailability;

import lombok.extern.slf4j.Slf4j;

/**
 * 검증을 통과한(VALIDATED) 일지를 아동 × 날짜 × 기관 묶음으로 요약 에이전트에 보내고 결과를 남긴다. 성공하면
 * 묶음의 일지가 GATE1_PENDING(교사 1차 검토 대기)이 되고, 재시도까지 근거가 남은 문장이 없으면 SUMMARY_FAILED(다시
 * 요약할 수 있음), 그 밖의 실패(4xx, 응답 해석 실패, 빈 본문, 근거 재검사)는 SYSTEM_ERROR(관리자 확인)가 된다 (#180).
 *
 * <p>구조는 {@link com.itda.backend.service.validation.ValidationWorker} 와 같고 처리 단위만 일지가 아니라 묶음이다.
 * 한 묶음씩 순서대로 처리하고, 실패는 그 묶음만 남긴다. 집기 전에 AI 상태를 보고, 재시도까지 응답이 없으면 지금 묶음을 포함해 나머지를 되돌려놓고 멈춘다 — 검증 워커와 다른 점이다.
 * AI {@code /health} 는 LLM 이 살아 있는지까지는 보지 않아서, LLM 장애가 이어지면 묶음마다 실패로 끝나기 때문이다.
 * 언제 묶음이 준비되는지는 {@link SummaryService#claimReadyGroups} 가 정한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.summary.worker", name = "enabled", havingValue = "true")
public class SummaryWorker {

    private final SummaryService summaryService;
    private final SummaryAgentClient client;
    private final SummaryResultRecorder recorder;
    private final int batchSize;
    private final AiAvailability availability;

    /** 앱이 켜진 뒤 첫 실행인지. 되돌리기를 여기서 하는 이유는 MatchingWorker 와 같다 (집기와 겹치지 않게). */
    private boolean released;

    /** AI 가 꺼져 있다는 로그를 상태가 바뀔 때만 남기려고 둔다 (5초마다 같은 경고가 쌓이지 않게). */
    private boolean agentDown;

    public SummaryWorker(
            SummaryService summaryService,
            SummaryAgentClient client,
            SummaryResultRecorder recorder,
            SummaryProperties properties,
            AiAvailability availability) {
        this.summaryService = summaryService;
        this.client = client;
        this.recorder = recorder;
        this.batchSize = properties.worker().batchSize();
        this.availability = availability;
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
                // 지금 묶음도 되돌린다. AI·LLM 이 잠깐 죽은 것으로 묶음을 실패로 끝내 버리면 교사나 관리자가
                // 다시 돌릴 때까지 그 일지들이 Gate 1 에 오지 못한다.
                summaryService.release(claimed.subList(i, claimed.size()));
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
        availability.reportHealth(available);
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
            // 집은 뒤 그 기관 동의가 철회됐으면 AI 에 보내지 않는다 (#332). 앞 묶음을 요약하는 동안에도 철회될 수 있다.
            // 아이 행은 잠그지 않는다 — AI 호출 내내 잠그면 그동안 동의 변경이 멈춘다. 그래서 이 확인 직후에 철회되면 원문은
            // 보내질 수 있다. 결과를 남길 때는 요약 행 → 아이 행을 잠근 뒤 한 번 더 보므로, 철회가 먼저 커밋됐으면 저장하지
            // 않는다 (#398).
            if (recorder.closeIfConsentRevoked(claimed)) {
                return Outcome.DONE;
            }
            SummaryAgentRequest request = summaryService.prepareRequest(claimed);
            SummaryAgentReply reply;
            if (NothingNotable.matches(request)) {
                // "특이사항 없음" 뿐인 묶음은 AI 를 부르지 않고 요약할 내용 없음으로 Gate 1 에 올린다 (#448).
                reply = NothingNotable.reply(request);
            } else {
                reply = client.summarize(request);
                availability.reportCallSucceeded();
            }
            recorder.record(claimed, request, reply);
            return Outcome.DONE;
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("stopping summary worker during shutdown group={}", claimed.group());
                return Outcome.SHUTTING_DOWN;
            }
            if (e instanceof AiAgentUnavailableException) {
                log.warn("summary agent unavailable, releasing group={} journalEntryIds={}",
                        claimed.group(), claimed.journalEntryIds(), e);
                // /health 는 LLM 까지 보지 않으므로 여기서 멈춘 것을 AI 장애 배너에 따로 알린다 (#370).
                availability.reportCallGaveUp();
                return Outcome.AGENT_DOWN;
            }
            if (e instanceof SummaryNoGroundedClaimsException) {
                log.warn("summary has no grounded claims group={} journalEntryIds={}",
                        claimed.group(), claimed.journalEntryIds(), e);
                recordFailure(claimed);
                return Outcome.DONE;
            }
            FailureReason reason = AgentFailureReasons.of(e);
            log.warn("summary failed group={} journalEntryIds={} reason={}",
                    claimed.group(), claimed.journalEntryIds(), reason, e);
            recordSystemError(claimed, reason);
            return Outcome.DONE;
        }
    }

    private void recordFailure(ClaimedSummaryGroup claimed) {
        try {
            recorder.recordFailure(claimed);
        } catch (RuntimeException recordError) {
            log.error("could not record summary failure group={}", claimed.group(), recordError);
        }
    }

    private void recordSystemError(ClaimedSummaryGroup claimed, FailureReason reason) {
        try {
            recorder.recordSystemError(claimed, reason);
        } catch (RuntimeException recordError) {
            log.error("could not record summary system error group={}", claimed.group(), recordError);
        }
    }

    private enum Outcome {
        DONE,
        AGENT_DOWN,
        SHUTTING_DOWN
    }
}
