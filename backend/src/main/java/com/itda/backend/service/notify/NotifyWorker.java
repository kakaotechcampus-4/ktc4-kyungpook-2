package com.itda.backend.service.notify;

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

import com.itda.backend.domain.insight.InsightNotification;
import com.itda.backend.domain.insight.InsightStatus;
import com.itda.backend.dto.request.notify.NotifyAgentRequest;
import com.itda.backend.dto.response.notify.NotifyAgentResponse;
import com.itda.backend.exception.agent.AiAgentUnavailableException;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.service.agent.AiAvailability;
import com.itda.backend.service.insight.InsightProperties;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.notify.worker", name = "enabled", havingValue = "true")
public class NotifyWorker {

    private final NotifyService notifyService;
    private final NotifyAgentClient client;
    private final NotifyRecorder recorder;
    private final InsightResultRepository insightResultRepository;
    private final InsightProperties insightProperties;
    private final int batchSize;
    private final AiAvailability availability;

    private boolean released;
    private boolean agentDown;

    public NotifyWorker(NotifyService notifyService, NotifyAgentClient client, NotifyRecorder recorder,
            InsightResultRepository insightResultRepository, InsightProperties insightProperties,
            NotifyProperties properties, AiAvailability availability) {
        this.notifyService = notifyService;
        this.client = client;
        this.recorder = recorder;
        this.insightResultRepository = insightResultRepository;
        this.insightProperties = insightProperties;
        this.batchSize = properties.worker().batchSize();
        this.availability = availability;
    }

    @Scheduled(fixedDelayString = "${app.notify.worker.delay-ms}",
            initialDelayString = "${app.notify.worker.initial-delay-ms:0}")
    public void run() {
        if (!released) {
            int count = recorder.releaseStuck();
            if (count > 0) {
                log.info("released {} notifications stuck in RUNNING", count);
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
            Optional<PreparedNotify> prepared =
                    notifyService.prepare(period.childId(), period.from(), period.to());
            if (prepared.isEmpty()) {
                continue;
            }
            if (!recorder.needsRun(period.childId(), period.from(), period.to(),
                    prepared.get().lastInsightId())) {
                continue;
            }

            Optional<InsightNotification> claimed = claim(period);
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
        LocalDateTime since = LocalDateTime.now().minusDays(insightProperties.lookbackDays());
        return insightResultRepository.findApprovedPeriodsSince(InsightStatus.APPROVED, since).stream()
                .map(row -> new Period(row.childId(), row.periodFrom(), row.periodTo()))
                .distinct()
                .sorted(Comparator.comparing(Period::to).reversed().thenComparing(Period::childId))
                .toList();
    }

    record Period(Long childId, LocalDate from, LocalDate to) {
    }

    static String notificationIdOf(Period period, Long lastInsightId) {
        return period.childId() + ":" + period.from() + ":" + period.to() + ":" + lastInsightId;
    }

    private Optional<InsightNotification> claim(Period period) {
        try {
            return recorder.claim(period.childId(), period.from(), period.to());
        } catch (DataIntegrityViolationException | UnexpectedRollbackException e) {
            log.debug("notification already claimed childId={} period={}..{}",
                    period.childId(), period.from(), period.to());
            return Optional.empty();
        }
    }

    private boolean process(InsightNotification notification, PreparedNotify prepared) {
        Period period = new Period(notification.getChildId(),
                notification.getPeriodFrom(), notification.getPeriodTo());
        NotifyAgentRequest base = prepared.request();
        NotifyAgentRequest request = new NotifyAgentRequest(
                base.childId(), base.childName(), base.periodFrom(), base.periodTo(), base.insights(),
                notificationIdOf(period, prepared.lastInsightId()), base.otherChildNames());
        try {
            NotifyAgentResponse response = client.send(request);
            availability.reportCallSucceeded();
            recorder.record(notification, response, prepared.insightCount(), prepared.lastInsightId());
            return true;
        } catch (AiAgentUnavailableException e) {
            String reason = client.failureOf(e);
            log.warn("notify agent unavailable, stopping this turn childId={} period={}..{} reason={}",
                    period.childId(), period.from(), period.to(), reason, e);
            recorder.recordFailure(notification, reason);
            availability.reportCallGaveUp();
            return false;
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("shutting down, notification left for the next start childId={}", period.childId());
                return false;
            }
            String reason = client.failureOf(e);
            log.warn("notify call failed, recorded as failure childId={} period={}..{} reason={}",
                    period.childId(), period.from(), period.to(), reason, e);
            recorder.recordFailure(notification, reason);
            return true;
        }
    }

    private boolean agentAvailable() {
        boolean available = client.isAvailable();
        availability.reportHealth(available);
        if (!available && !agentDown) {
            log.warn("notify agent is down, skipping turns until it is back");
            agentDown = true;
        } else if (available && agentDown) {
            log.info("notify agent is back");
            agentDown = false;
        }
        return available;
    }
}
