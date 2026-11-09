package com.itda.backend.service.notify;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.insight.InsightNotification;
import com.itda.backend.domain.insight.InsightNotificationStatus;
import com.itda.backend.dto.response.notify.NotifyAgentResponse;
import com.itda.backend.repository.insight.InsightNotificationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyRecorder {

    private final InsightNotificationRepository repository;
    private final NotifyProperties properties;

    @Transactional(readOnly = true)
    public boolean needsRun(Long childId, LocalDate periodFrom, LocalDate periodTo, Long lastInsightId) {
        Optional<InsightNotification> last = repository
                .findFirstByChildIdAndPeriodFromAndPeriodToOrderByRevisionDesc(childId, periodFrom, periodTo);
        if (last.isEmpty()) {
            return true;
        }
        InsightNotification notification = last.get();
        return switch (notification.getStatus()) {
            case RUNNING -> false;
            case SENT, NO_RECIPIENT, BLOCKED -> hasNewMaterial(notification, lastInsightId);
            case FAILED -> backoffPassed(childId, periodFrom, periodTo, notification);
        };
    }

    private boolean hasNewMaterial(InsightNotification notification, Long lastInsightId) {
        Long seen = notification.getLastInsightId();
        return seen == null || (lastInsightId != null && lastInsightId > seen);
    }

    private boolean backoffPassed(Long childId, LocalDate periodFrom, LocalDate periodTo,
            InsightNotification last) {
        List<Duration> backoffs = properties.failureBackoffs();
        if (backoffs == null || backoffs.isEmpty()) {
            return true;
        }
        int consecutive = 0;
        for (InsightNotification notification : repository
                .findByChildIdAndPeriodFromAndPeriodToOrderByRevisionDesc(childId, periodFrom, periodTo)) {
            if (notification.getStatus() != InsightNotificationStatus.FAILED) {
                break;
            }
            consecutive++;
        }
        Duration wait = backoffs.get(Math.min(consecutive, backoffs.size()) - 1);
        return last.getUpdatedAt().plus(wait).isBefore(LocalDateTime.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<InsightNotification> claim(Long childId, LocalDate periodFrom, LocalDate periodTo) {
        int revision = repository
                .findFirstByChildIdAndPeriodFromAndPeriodToOrderByRevisionDesc(childId, periodFrom, periodTo)
                .map(notification -> notification.getRevision() + 1)
                .orElse(1);
        return Optional.of(repository.saveAndFlush(
                InsightNotification.claim(childId, periodFrom, periodTo, revision)));
    }

    @Transactional
    public void record(InsightNotification notification, NotifyAgentResponse response,
            int insightCount, Long lastInsightId) {
        InsightNotification attached = repository.findById(notification.getId()).orElse(notification);
        if (response.isBlocked()) {
            String reasons = response.blockedLines() == null ? "" : response.blockedLines().stream()
                    .flatMap(line -> line.reasons() == null ? java.util.stream.Stream.<String>empty()
                            : line.reasons().stream())
                    .distinct()
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
            log.warn("notify blocked childId={} period={}..{} lines={} reasons={}",
                    attached.getChildId(), attached.getPeriodFrom(), attached.getPeriodTo(),
                    response.blockedLines() == null ? 0 : response.blockedLines().size(), reasons);
            attached.markBlocked(insightCount, lastInsightId, reasons);
        } else if (response.isNoRecipient()) {
            log.info("no guardian to notify childId={} period={}..{}",
                    attached.getChildId(), attached.getPeriodFrom(), attached.getPeriodTo());
            attached.markNoRecipient(insightCount, lastInsightId);
        } else {
            attached.markSent(insightCount, lastInsightId, response.messageId());
        }
        repository.save(attached);
    }

    @Transactional
    public void recordFailure(InsightNotification notification, String reason) {
        InsightNotification attached = repository.findById(notification.getId()).orElse(notification);
        attached.fail(reason);
        repository.save(attached);
    }

    @Transactional
    public int releaseStuck() {
        List<InsightNotification> stuck = repository.findByStatus(InsightNotificationStatus.RUNNING);
        stuck.forEach(notification -> notification.fail("interrupted"));
        repository.saveAll(stuck);
        return stuck.size();
    }
}
