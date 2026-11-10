package com.itda.backend.service.matching;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.itda.backend.domain.record.FailureReason;
import com.itda.backend.dto.request.matching.MatchingAgentRequest;
import com.itda.backend.exception.agent.AgentContentTooLongException;
import com.itda.backend.exception.agent.AiAgentUnavailableException;
import com.itda.backend.service.agent.AgentDownProperties;
import com.itda.backend.service.agent.AiAvailability;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.matching.worker", name = "enabled", havingValue = "true")
public class MatchingWorker {

    private final MatchingService matchingService;
    private final MatchingAgentClient client;
    private final MatchingResultRecorder recorder;
    private final int batchSize;
    private final int maxReleases;
    private final Duration pause;
    private final Clock clock;
    private final AiAvailability availability;

    private boolean released;

    private boolean agentDown;

    private Instant pausedUntil = Instant.MIN;

    public MatchingWorker(
            MatchingService matchingService,
            MatchingAgentClient client,
            MatchingResultRecorder recorder,
            MatchingProperties properties,
            AgentDownProperties agentDown,
            Clock clock,
            AiAvailability availability) {
        this.matchingService = matchingService;
        this.client = client;
        this.recorder = recorder;
        this.batchSize = properties.worker().batchSize();
        this.maxReleases = agentDown.maxReleases();
        this.pause = agentDown.pause();
        this.clock = clock;
        this.availability = availability;
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
        if (clock.instant().isBefore(pausedUntil) || !agentAvailable()) {
            return;
        }

        List<Long> claimed = matchingService.claimPending(batchSize);
        for (int i = 0; i < claimed.size(); i++) {
            Outcome outcome = process(claimed.get(i));
            if (outcome == Outcome.AGENT_DOWN) {
                pausedUntil = clock.instant().plus(pause);
                log.info("matching agent down, pausing for {}", pause);
                matchingService.release(claimed.subList(i + 1, claimed.size()));
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
            availability.reportCallSucceeded();
            recorder.record(journalEntryId, reply);
            return Outcome.DONE;
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("stopping matching worker during shutdown journalEntryId={}", journalEntryId);
                return Outcome.SHUTTING_DOWN;
            }
            if (e instanceof AiAgentUnavailableException) {
                log.warn("matching agent unavailable journalEntryId={}", journalEntryId, e);
                availability.reportCallGaveUp();
                return releaseOrFail(journalEntryId);
            }
            if (e instanceof AgentContentTooLongException) {
                log.warn("matching skipped, content too long journalEntryId={}", journalEntryId, e);
                recordSystemError(journalEntryId, FailureReason.CONTENT_TOO_LONG);
                return Outcome.DONE;
            }
            log.warn("matching failed journalEntryId={}", journalEntryId, e);
            recordFailure(journalEntryId);
            return Outcome.DONE;
        }
    }

    private void recordSystemError(Long journalEntryId, FailureReason reason) {
        try {
            recorder.recordSystemError(journalEntryId, reason);
        } catch (RuntimeException recordError) {
            log.error("could not record matching system error journalEntryId={}", journalEntryId, recordError);
        }
    }

    private Outcome releaseOrFail(Long journalEntryId) {
        try {
            if (matchingService.releaseAfterAgentDown(journalEntryId, maxReleases)) {
                return Outcome.AGENT_DOWN;
            }
        } catch (RuntimeException releaseError) {
            log.error("could not release matching after agent down journalEntryId={}", journalEntryId, releaseError);
            return Outcome.AGENT_DOWN;
        }
        log.warn("matching agent down {} times in a row, recording as failure journalEntryId={}",
                maxReleases + 1, journalEntryId);
        recordFailure(journalEntryId);
        return Outcome.DONE;
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
