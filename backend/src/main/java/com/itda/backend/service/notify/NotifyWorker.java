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

/**
 * Gate 2 에서 승인된 인사이트를 보호자 메일로 내보낸다 (#419).
 *
 * <p>구조는 {@link com.itda.backend.service.insight.InsightWorker} 와 같고 처리 단위도 <b>아동 × 기간</b>이다.
 *
 * <p><b>왜 승인 트랜잭션 안에서 보내지 않나</b> — {@code InsightGate2Service} 는 "기관 경계를 넘는 마지막
 * 관문" 이라 되돌리는 경로를 두지 않는다. 거기서 메일을 보내면 메일 서버가 느리거나 죽었을 때 교사 화면이
 * 멈추고, 발송 실패가 승인까지 되돌린다. <b>승인은 실패하지 않아야 한다.</b> 그래서 승인은 열람 권한만 열고,
 * 발송은 이 워커가 따로 한다.
 *
 * <p><b>왜 인사이트마다 보내지 않나</b> — 교사는 Gate 2 승인을 한 건씩 누른다. 승인마다 보내면 세 건을
 * 승인한 날 보호자가 메일 세 통을 받는다. 아동 × 기간으로 묶어 한 통으로 보낸다.
 */
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

    /** 앱이 켜진 뒤 첫 실행인지. 되돌리기를 여기서 하는 이유는 다른 워커들과 같다 (집기와 겹치지 않게). */
    private boolean released;
    /** AI 가 꺼져 있다는 로그를 상태가 바뀔 때만 남기려고 둔다. */
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
            // 재료를 먼저 본다. 없으면 집지 않는다 — 집어서 SENT 로 남기면 뒤에 승인된 인사이트가 영영 안 나간다.
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

    /**
     * 이번 차례에 볼 (아이, 기간) 후보 — <b>최근에 Gate 2 를 통과한 인사이트</b>가 속한 기간이다.
     *
     * <p>기간 계산은 인사이트 워커와 같은 값을 쓴다 ({@code app.insight.period-days} · {@code zone}).
     * 인사이트가 그 기간 키로 만들어졌으므로 발송도 같은 키여야 한다 — 다르면 한 기간이 여러 메일로 쪼개진다.
     */
    private List<Period> candidates() {
        LocalDateTime since = LocalDateTime.now().minusDays(insightProperties.lookbackDays());
        return insightResultRepository.findApprovedPeriodsSince(InsightStatus.APPROVED, since).stream()
                .map(row -> new Period(row.childId(), row.periodFrom(), row.periodTo()))
                .distinct()
                // 최근 기간부터 — 늦은 승인으로 옛 기간이 쌓여도 지난주가 밀리지 않게 한다.
                .sorted(Comparator.comparing(Period::to).reversed().thenComparing(Period::childId))
                .toList();
    }

    /** package-private 이다 — 멱등성 키 계산을 테스트가 직접 본다. */
    record Period(Long childId, LocalDate from, LocalDate to) {
    }

    /**
     * 멱등성 키. <b>판 번호가 아니라 재료에 묶는다.</b>
     *
     * <p>앱이 메일을 보낸 직후 죽으면 그 판은 {@code RUNNING} 으로 남고, 되살아난 워커가 다시 집는다.
     * 그때 판 번호는 올라가지만 재료({@code lastInsightId})는 그대로다 — 키가 같으므로 AI 쪽 MCP 서버가
     * 두 번째 발송을 막고 처음 {@code message_id} 를 돌려준다 (AI/notify/CRITERIA.md §5).
     *
     * <p>뒤에 인사이트가 더 승인되면 {@code lastInsightId} 가 커져 키가 달라진다. 그때는 새 메일이 맞다.
     */
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

    /** @return 계속 진행해도 되면 {@code true}, AI 가 응답하지 않아 이번 차례를 멈춰야 하면 {@code false} */
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
            // 모델 고장이거나 메일 서버 장애다. 둘 다 다시 하면 될 수 있다. 이 아이는 FAILED 로 두고 멈춘다.
            String reason = client.failureOf(e);
            log.warn("notify agent unavailable, stopping this turn childId={} period={}..{} reason={}",
                    period.childId(), period.from(), period.to(), reason, e);
            recorder.recordFailure(notification, reason);
            availability.reportCallGaveUp();
            return false;
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                // 앱이 내려가는 중이다. RUNNING 으로 두면 다음에 켜질 때 releaseStuck 이 되돌린다.
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
