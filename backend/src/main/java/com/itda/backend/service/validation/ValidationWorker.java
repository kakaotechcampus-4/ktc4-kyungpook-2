package com.itda.backend.service.validation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.itda.backend.domain.record.FailureReason;
import com.itda.backend.exception.agent.AiAgentUnavailableException;
import com.itda.backend.service.agent.AgentDownProperties;
import com.itda.backend.service.agent.AiAvailability;
import com.itda.backend.service.agent.AgentFailureReasons;

import lombok.extern.slf4j.Slf4j;

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
    private final AiAvailability availability;

    private boolean released;

    private boolean agentDown;

    private Instant pausedUntil = Instant.MIN;

    public ValidationWorker(
            ValidationService validationService,
            ValidationAgentClient client,
            ValidationResultRecorder recorder,
            ValidationProperties properties,
            AgentDownProperties agentDown,
            Clock clock,
            AiAvailability availability) {
        this.validationService = validationService;
        this.client = client;
        this.recorder = recorder;
        this.batchSize = properties.worker().batchSize();
        this.maxReleases = agentDown.maxReleases();
        this.pause = agentDown.pause();
        this.clock = clock;
        this.availability = availability;
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
                pausedUntil = clock.instant().plus(pause);
                log.info("validation agent down, pausing for {}", pause);
                validationService.release(claimed.subList(i + 1, claimed.size()));
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
            availability.reportCallSucceeded();
            recorder.record(target, reply);
            return Outcome.DONE;
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("stopping validation worker during shutdown journalEntryId={}", journalEntryId);
                return Outcome.SHUTTING_DOWN;
            }
            if (e instanceof AiAgentUnavailableException) {
                log.warn("validation agent unavailable journalEntryId={}", journalEntryId, e);
                availability.reportCallGaveUp();
                return releaseOrFail(journalEntryId);
            }
            FailureReason reason = AgentFailureReasons.of(e);
            log.warn("validation failed journalEntryId={} reason={}", journalEntryId, reason, e);
            recordFailure(journalEntryId, reason);
            return Outcome.DONE;
        }
    }

    private Outcome releaseOrFail(Long journalEntryId) {
        try {
            if (validationService.releaseAfterAgentDown(journalEntryId, maxReleases)) {
                return Outcome.AGENT_DOWN;
            }
        } catch (RuntimeException releaseError) {
            log.error("could not release validation after agent down journalEntryId={}", journalEntryId, releaseError);
            return Outcome.AGENT_DOWN;
        }
        log.warn("validation agent down {} times in a row, recording as failure journalEntryId={}",
                maxReleases + 1, journalEntryId);
        recordFailure(journalEntryId, FailureReason.AGENT_UNAVAILABLE);
        return Outcome.DONE;
    }

    private void recordFailure(Long journalEntryId, FailureReason reason) {
        try {
            recorder.recordFailure(journalEntryId, reason);
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
