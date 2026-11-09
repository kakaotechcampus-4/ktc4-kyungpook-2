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

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.summary.worker", name = "enabled", havingValue = "true")
public class SummaryWorker {

    private final SummaryService summaryService;
    private final SummaryAgentClient client;
    private final SummaryResultRecorder recorder;
    private final int batchSize;
    private final AiAvailability availability;

    private boolean released;

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
                summaryService.release(claimed.subList(i, claimed.size()));
                return;
            }
            if (outcome == Outcome.SHUTTING_DOWN) {
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
            if (recorder.closeIfConsentRevoked(claimed)) {
                return Outcome.DONE;
            }
            SummaryAgentRequest request = summaryService.prepareRequest(claimed);
            SummaryAgentReply reply;
            if (NothingNotable.matches(request)) {
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
