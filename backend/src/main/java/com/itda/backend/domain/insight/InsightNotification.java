package com.itda.backend.domain.insight;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.itda.backend.global.jpa.TimestampedEntity;

/**
 * 알림 워커가 "이 아이의 이 기간은 보호자에게 보냈다" 를 남기는 자리 (#419).
 *
 * <p>구조는 {@link InsightRun} 과 같다. <b>집는 것 자체가 INSERT 고</b>, 워커 둘이 같은 아이·같은 기간을
 * 동시에 집으면 UNIQUE 에 걸린 쪽이 진다.
 *
 * <p><b>왜 insight_result 에 칸을 더하지 않았나</b> — 메일 한 통에 인사이트 여러 개가 들어간다. 결과 행마다
 * 상태를 두면 "세 개 중 둘만 보냈다" 같은 상태가 생기는데, 메일은 그렇게 나가지 않는다. 보내는 단위가
 * <b>아동 × 기간</b>이라 기록도 그 단위여야 한다.
 *
 * <p>승인({@code InsightStatus.APPROVED})과 발송은 다른 축이다. 승인은 열람 권한을 여는 일이고
 * ({@code InsightGate2Service}), 발송은 그 뒤에 따로 일어난다 — 메일 서버가 죽어도 승인은 그대로여야 한다.
 *
 * <p>실행 이력이라 지우지 않는다. 실패한 판도 고치지 않고 {@code revision + 1} 로 새로 집는다.
 */
@Entity
@Table(
        name = "insight_notification",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_insight_notification_child_period_revision",
                columnNames = {"child_id", "period_from", "period_to", "revision"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InsightNotification extends TimestampedEntity {

    private static final int FAILURE_REASON_LENGTH = 60;
    private static final int MESSAGE_ID_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    @Column(name = "period_from", nullable = false)
    private LocalDate periodFrom;

    @Column(name = "period_to", nullable = false)
    private LocalDate periodTo;

    /** 같은 아동·같은 기간의 몇 번째 발송 시도인지. 1 부터. */
    @Column(nullable = false)
    private Integer revision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InsightNotificationStatus status;

    /** 이번 메일에 담은 인사이트 수. {@code SENT} · {@code BLOCKED} 일 때 채운다. */
    @Column(name = "insight_count")
    private Integer insightCount;

    /**
     * 그 발송이 본 {@code insight_result} 중 가장 큰 id — <b>어디까지 보냈나</b>.
     *
     * <p>{@link InsightRun#getLastContextId()} 와 같은 장치다. 교사가 Gate 2 승인을 틈틈이 하므로
     * "이 기간은 보냈다" 만 기억하면 뒤에 승인된 인사이트가 영영 안 나간다.
     */
    @Column(name = "last_insight_id")
    private Long lastInsightId;

    /**
     * 메일 식별자. AI 가 MCP 서버에서 받아 돌려준 값이다. {@code SENT} 일 때만 채운다.
     *
     * <p>보호자가 한 명도 없으면 {@code null} 이면서 {@code SENT} 다 — 보낼 사람이 없는 것은 장애가 아니다.
     */
    @Column(name = "message_id", length = MESSAGE_ID_LENGTH)
    private String messageId;

    /**
     * 왜 못 보냈나. {@code FAILED} · {@code BLOCKED} 일 때 채운다.
     *
     * <p>{@code BLOCKED} 면 AI 가 준 걸린 이유({@code 다른아이이름} 같은 것)를, {@code FAILED} 면
     * {@code delivery_failed} · {@code llm_unavailable:timeout} 처럼 AI 쪽 사유를 그대로 남긴다.
     * enum 이 아니라 문자열인 이유는 {@link InsightRun#getFailureReason()} 과 같다.
     *
     * <p>⚠️ <b>막힌 문장 자체는 여기 넣지 않는다.</b> 아이 기록이라 DB 에 옮겨 적지 않는다 —
     * {@code FailureReason} 주석과 같은 기준이다. 문장은 로그로만 간다.
     */
    @Column(name = "failure_reason", length = FAILURE_REASON_LENGTH)
    private String failureReason;

    private InsightNotification(Long childId, LocalDate periodFrom, LocalDate periodTo, int revision) {
        this.childId = childId;
        this.periodFrom = periodFrom;
        this.periodTo = periodTo;
        this.revision = revision;
        this.status = InsightNotificationStatus.RUNNING;
    }

    /** 한 아이의 한 기간을 집는다. 저장이 UNIQUE 에 걸리면 다른 워커가 먼저 집은 것이다. */
    public static InsightNotification claim(Long childId, LocalDate periodFrom, LocalDate periodTo, int revision) {
        if (childId == null || periodFrom == null || periodTo == null) {
            throw new IllegalArgumentException("알림의 아동·기간은 필수입니다.");
        }
        if (periodTo.isBefore(periodFrom)) {
            throw new IllegalArgumentException("알림 기간의 끝이 시작보다 빠릅니다: " + periodFrom + " ~ " + periodTo);
        }
        if (revision < 1) {
            throw new IllegalArgumentException("알림 판 번호는 1부터입니다: " + revision);
        }
        return new InsightNotification(childId, periodFrom, periodTo, revision);
    }

    /**
     * 메일이 나갔다.
     *
     * @param messageId MCP 서버가 준 식별자
     */
    public void markSent(int insightCount, Long lastInsightId, String messageId) {
        this.status = InsightNotificationStatus.SENT;
        this.insightCount = insightCount;
        this.lastInsightId = lastInsightId;
        this.messageId = truncate(messageId, MESSAGE_ID_LENGTH);
    }

    /**
     * 글은 통과했는데 받을 보호자가 없었다. {@code SENT} 와 나눈다 (#430 리뷰) — 장애는 아니지만
     * 메일도 안 나갔다. 합치면 교사가 "보냈다" 고 믿는다.
     */
    public void markNoRecipient(int insightCount, Long lastInsightId) {
        this.status = InsightNotificationStatus.NO_RECIPIENT;
        this.insightCount = insightCount;
        this.lastInsightId = lastInsightId;
    }

    /**
     * 근거 검사에 걸려 보내지 않았다. <b>다시 집지 않는다</b> — 재료가 그대로면 결과도 같다.
     *
     * <p>{@code lastInsightId} 를 채우는 이유: 뒤에 인사이트가 더 승인되면 재료가 달라지므로 그때는
     * 새 판으로 다시 집어야 한다. 눈금이 없으면 영영 안 집히거나 매번 다시 집는다.
     */
    public void markBlocked(int insightCount, Long lastInsightId, String reason) {
        this.status = InsightNotificationStatus.BLOCKED;
        this.insightCount = insightCount;
        this.lastInsightId = lastInsightId;
        this.failureReason = truncate(reason, FAILURE_REASON_LENGTH);
    }

    /** 모델이나 메일 서버가 멈췄다. 건수는 채우지 않는다 — 보낸 것과 구별돼야 한다. */
    public void fail(String reason) {
        this.status = InsightNotificationStatus.FAILED;
        this.failureReason = truncate(reason, FAILURE_REASON_LENGTH);
    }

    /** 낯선 긴 값이 와도 저장이 터지지 않게 자른다. 터지면 그 판이 RUNNING 으로 남아 다시 켤 때까지 안 집힌다. */
    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
