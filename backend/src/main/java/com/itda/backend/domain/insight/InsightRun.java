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
        name = "insight_run",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_insight_run_child_period_revision",
                columnNames = {"child_id", "period_from", "period_to", "revision"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InsightRun extends TimestampedEntity {

    private static final int FAILURE_REASON_LENGTH = 60;

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
    private InsightRunStatus status;

    @Column(name = "insight_count")
    private Integer insightCount;

    @Column(name = "last_context_id")
    private Long lastContextId;

    @Column(name = "failure_reason", length = FAILURE_REASON_LENGTH)
    private String failureReason;

    private InsightRun(Long childId, LocalDate periodFrom, LocalDate periodTo, int revision) {
        this.childId = childId;
        this.periodFrom = periodFrom;
        this.periodTo = periodTo;
        this.revision = revision;
        this.status = InsightRunStatus.RUNNING;
    }

    public static InsightRun claim(Long childId, LocalDate periodFrom, LocalDate periodTo, int revision) {
        if (childId == null || periodFrom == null || periodTo == null) {
            throw new IllegalArgumentException("인사이트 실행의 아동·기간은 필수입니다.");
        }
        if (periodTo.isBefore(periodFrom)) {
            throw new IllegalArgumentException("인사이트 기간의 끝이 시작보다 빠릅니다: " + periodFrom + " ~ " + periodTo);
        }
        if (revision < 1) {
            throw new IllegalArgumentException("인사이트 판 번호는 1부터입니다: " + revision);
        }
        return new InsightRun(childId, periodFrom, periodTo, revision);
    }

    public void finish(int count, Long lastContextId) {
        if (count < 0) {
            throw new IllegalArgumentException("인사이트 수는 음수가 될 수 없습니다: " + count);
        }
        this.status = InsightRunStatus.DONE;
        this.insightCount = count;
        this.lastContextId = lastContextId;
    }

    public void fail(String reason) {
        this.status = InsightRunStatus.FAILED;
        this.failureReason = reason == null || reason.length() <= FAILURE_REASON_LENGTH
                ? reason : reason.substring(0, FAILURE_REASON_LENGTH);
    }
}
