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

/**
 * 발송 기록을 남긴다 (#419). 구조는 {@code InsightResultRecorder} 와 같다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyRecorder {

    private final InsightNotificationRepository repository;
    private final NotifyProperties properties;

    /**
     * 이 아이·이 기간을 지금 보내야 하나.
     *
     * <p>보내지 않는 경우가 셋이다.
     * <ul>
     *   <li>{@code SENT} — 이미 나갔다. 뒤에 인사이트가 더 승인됐으면 {@code lastInsightId} 가 달라져 다시 본다</li>
     *   <li>{@code NO_RECIPIENT} — 받을 사람이 없었다. 보호자가 이메일을 넣으면 새 인사이트와 함께 나간다</li>
     *   <li>{@code BLOCKED} — 재료가 그대로면 다시 써도 같은 글이 나온다. 재료가 늘었을 때만 다시 본다</li>
     *   <li>{@code RUNNING} — 누가 돌고 있다</li>
     * </ul>
     * {@code FAILED} 는 연속 실패 횟수만큼 기다린 뒤 다시 집는다 (#266 과 같은 장치).
     *
     * @param lastInsightId 지금 보이는 승인 인사이트 중 가장 큰 id
     */
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

    /**
     * 지난번 이후로 승인된 인사이트가 더 있나.
     *
     * <p>눈금({@code lastInsightId})이 {@code null} 이면 모른다는 뜻이라 다시 본다 — 이 컬럼이 생기기 전
     * 행이 그렇다. {@code InsightRun.lastContextId} 와 같은 규칙이다.
     */
    private boolean hasNewMaterial(InsightNotification notification, Long lastInsightId) {
        Long seen = notification.getLastInsightId();
        return seen == null || (lastInsightId != null && lastInsightId > seen);
    }

    /** 연달아 실패한 만큼 기다렸나. 기다리지 않으면 계속 실패하는 아이가 맨 앞에 와서 뒤를 막는다. */
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

    /**
     * 집는다. 저장이 UNIQUE 에 걸리면 다른 워커가 먼저 집은 것이다 — 그 예외는 트랜잭션 경계 밖에서 잡는다.
     *
     * <p>{@code REQUIRES_NEW} 인 이유는 인사이트 워커와 같다. 집기는 그 자체로 커밋돼야 뒤이은 실패가
     * 집은 기록까지 되돌리지 않는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<InsightNotification> claim(Long childId, LocalDate periodFrom, LocalDate periodTo) {
        int revision = repository
                .findFirstByChildIdAndPeriodFromAndPeriodToOrderByRevisionDesc(childId, periodFrom, periodTo)
                .map(notification -> notification.getRevision() + 1)
                .orElse(1);
        return Optional.of(repository.saveAndFlush(
                InsightNotification.claim(childId, periodFrom, periodTo, revision)));
    }

    /**
     * AI 응답을 기록한다.
     *
     * <p>{@code blocked} 는 실패가 아니다 — 200 으로 온 정상 결과고, 교사가 봐야 할 일이다.
     * 다시 집지 않으므로 {@code FAILED} 와 섞으면 안 된다.
     *
     * <p>⚠️ 막힌 <b>문장</b>은 저장하지 않는다. 아이 기록이라 DB 에 옮겨 적지 않는다 — 사유만 남기고
     * 문장은 로그로만 간다.
     */
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
            // 글은 통과했는데 받을 사람이 없었다. SENT 로 적으면 교사가 "보냈다" 고 믿는다 (#430 리뷰).
            log.info("no guardian to notify childId={} period={}..{}",
                    attached.getChildId(), attached.getPeriodFrom(), attached.getPeriodTo());
            attached.markNoRecipient(insightCount, lastInsightId);
        } else {
            // sent · nothing_to_send 둘 다 보낼 것을 보낸 상태다.
            attached.markSent(insightCount, lastInsightId, response.messageId());
        }
        repository.save(attached);
    }

    /** 모델·메일 서버 장애. 다음 차례에 {@code revision + 1} 로 다시 집는다. */
    @Transactional
    public void recordFailure(InsightNotification notification, String reason) {
        InsightNotification attached = repository.findById(notification.getId()).orElse(notification);
        attached.fail(reason);
        repository.save(attached);
    }

    /**
     * 앱이 죽어 {@code RUNNING} 으로 남은 발송을 {@code FAILED} 로 되돌린다.
     *
     * <p>⚠️ <b>그 메일은 이미 나갔을 수도 있다.</b> BE 는 알 수 없다. 다시 집으면 판 번호가 올라가지만
     * 멱등성 키({@code notification_id})는 판 번호가 아니라 <b>재료</b>에 묶여 있어 그대로다
     * ({@link NotifyWorker#notificationIdOf}). 그래서 AI 쪽 MCP 서버가 두 번째 발송을 막는다.
     */
    @Transactional
    public int releaseStuck() {
        List<InsightNotification> stuck = repository.findByStatus(InsightNotificationStatus.RUNNING);
        stuck.forEach(notification -> notification.fail("interrupted"));
        repository.saveAll(stuck);
        return stuck.size();
    }
}
