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

    @Column(nullable = false)
    private Integer revision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InsightNotificationStatus status;

    @Column(name = "insight_count")
    private Integer insightCount;

    @Column(name = "last_insight_id")
    private Long lastInsightId;

    @Column(name = "message_id", length = MESSAGE_ID_LENGTH)
    private String messageId;

    @Column(name = "failure_reason", length = FAILURE_REASON_LENGTH)
    private String failureReason;

    private InsightNotification(Long childId, LocalDate periodFrom, LocalDate periodTo, int revision) {
        this.childId = childId;
        this.periodFrom = periodFrom;
        this.periodTo = periodTo;
        this.revision = revision;
        this.status = InsightNotificationStatus.RUNNING;
    }

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

    public void markSent(int insightCount, Long lastInsightId, String messageId) {
        this.status = InsightNotificationStatus.SENT;
        this.insightCount = insightCount;
        this.lastInsightId = lastInsightId;
        this.messageId = truncate(messageId, MESSAGE_ID_LENGTH);
    }

    public void markNoRecipient(int insightCount, Long lastInsightId) {
        this.status = InsightNotificationStatus.NO_RECIPIENT;
        this.insightCount = insightCount;
        this.lastInsightId = lastInsightId;
    }

    public void markBlocked(int insightCount, Long lastInsightId, String reason) {
        this.status = InsightNotificationStatus.BLOCKED;
        this.insightCount = insightCount;
        this.lastInsightId = lastInsightId;
        this.failureReason = truncate(reason, FAILURE_REASON_LENGTH);
    }

    public void fail(String reason) {
        this.status = InsightNotificationStatus.FAILED;
        this.failureReason = truncate(reason, FAILURE_REASON_LENGTH);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
