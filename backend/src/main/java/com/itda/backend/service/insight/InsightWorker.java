package com.itda.backend.service.insight;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
import com.itda.backend.service.agent.AiAvailability;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.insight.worker", name = "enabled", havingValue = "true")
public class InsightWorker {

    private final InsightService insightService;
    private final InsightAgentClient client;
    private final InsightResultRecorder recorder;
    private final ChildContextRepository childContextRepository;
    private final int batchSize;
    private final InsightProperties properties;
    private final AiAvailability availability;

    private boolean released;
    private boolean agentDown;

    public InsightWorker(InsightService insightService, InsightAgentClient client,
            InsightResultRecorder recorder, ChildContextRepository childContextRepository,
            InsightProperties properties, AiAvailability availability) {
        this.insightService = insightService;
        this.client = client;
        this.recorder = recorder;
        this.childContextRepository = childContextRepository;
        this.batchSize = properties.worker().batchSize();
        this.properties = properties;
        this.availability = availability;
    }

    @Scheduled(fixedDelayString = "${app.insight.worker.delay-ms}",
            initialDelayString = "${app.insight.worker.initial-delay-ms:0}")
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

    private List<Period> candidates() {
        LocalDate lastEnd = properties.lastPeriodEnd(LocalDate.now(properties.zone()));
        return childContextRepository
                .findApprovedEntryDaysSince(LocalDateTime.now().minusDays(properties.lookbackDays())).stream()
                .map(day -> {
                    LocalDate to = properties.periodEndOf(day.entryDate());
                    return new Period(day.childId(), properties.periodStartOf(to), to);
                })
                .filter(period -> !period.to().isAfter(lastEnd))
                .distinct()
                .sorted(Comparator.comparing(Period::to).reversed().thenComparing(Period::childId))
                .toList();
    }

    private record Period(Long childId, LocalDate from, LocalDate to) {
    }

    private Optional<InsightRun> claim(Long childId, LocalDate periodFrom, LocalDate periodTo) {
        try {
            return recorder.claim(childId, periodFrom, periodTo);
        } catch (DataIntegrityViolationException | UnexpectedRollbackException e) {
            log.debug("insight run already claimed childId={} period={}..{}", childId, periodFrom, periodTo);
            return Optional.empty();
        }
    }

    private boolean process(InsightRun run, PreparedInsight prepared) {
        try {
            InsightAgentReply reply = client.analyze(prepared.request());
            availability.reportCallSucceeded();
            recorder.record(run, reply.response(), reply.rawJson(), prepared.lastContextId(), prepared.request());
            return true;
        } catch (AiAgentUnavailableException e) {
            String reason = client.failureOf(e);
            log.warn("insight agent unavailable, stopping this turn childId={} period={}..{} reason={}",
                    run.getChildId(), run.getPeriodFrom(), run.getPeriodTo(), reason, e);
            recorder.recordFailure(run, reason);
            availability.reportCallGaveUp();
            return false;
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("shutting down, insight run left for the next start childId={}", run.getChildId());
                return false;
            }
            String reason = client.failureOf(e);
            log.warn("insight call failed, recorded as failure childId={} period={}..{} reason={}",
                    run.getChildId(), run.getPeriodFrom(), run.getPeriodTo(), reason, e);
            recorder.recordFailure(run, reason);
            return true;
        }
    }

    private boolean agentAvailable() {
        boolean available = client.isAvailable();
        availability.reportHealth(available);
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
