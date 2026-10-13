package com.itda.backend.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 인사이트 워커가 "이 아이의 이 기간은 처리했다" 를 남기는 자리 (DB 스키마 §9.5).
 *
 * <p><b>왜 이 테이블만 따로 있나</b> — 매칭·검증·요약은 입력인 {@code journal_entry} 가 상태를 들고 있어서
 * 거기에 표시한다(§6.2). 인사이트의 입력은 {@code child_context} 인데 이건 승인 이력이라 고치지 않는다
 * — {@code updated_at} 조차 없다(§9.2). 표시를 남길 데가 없다.
 *
 * <p>결과 행이 생기는 것으로 갈음할 수도 없다. <b>인사이트가 0건인 것이 정상</b>이기 때문이다
 * ({@code AI/insight/CRITERIA.md} §3 — 억지로 만든 패턴이 과잉해석이다). 0건이면 {@code insight_result} 에
 * 행이 안 생기고, 그러면 워커가 돌 때마다 같은 아이에게 LLM 을 다시 부른다.
 *
 * <p><b>집는 것 자체가 INSERT 다.</b> 워커 둘이 같은 아이·같은 기간을 동시에 집으면 UNIQUE 에 걸린 쪽이
 * 진다 — {@code SummaryService.claimReadyGroups} 가 하던 일을 DB 제약이 대신한다. 판 번호를 여기서
 * 정하므로 {@code insight_result.revision} 의 경합도 함께 사라진다(§9.3).
 *
 * <p>실행 이력이라 지우지 않는다. 실패한 판도 고치지 않고 {@code revision + 1} 로 새로 집는다 (§3.2).
 */
@Entity
@Table(
        name = "insight_run",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_insight_run_child_period_revision",
                columnNames = {"child_id", "period_from", "period_to", "revision"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InsightRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    @Column(name = "period_from", nullable = false)
    private LocalDate periodFrom;

    @Column(name = "period_to", nullable = false)
    private LocalDate periodTo;

    /** 같은 아동·같은 기간의 몇 번째 실행인지. 1 부터. {@code insight_result.revision} 이 이 값을 받아 쓴다. */
    @Column(nullable = false)
    private Integer revision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InsightRunStatus status;

    /** 낸 인사이트 수. {@code DONE} 일 때만 채운다. <b>0 도 정상이다.</b> */
    @Column(name = "insight_count")
    private Integer insightCount;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private InsightRun(Long childId, LocalDate periodFrom, LocalDate periodTo, int revision) {
        this.childId = childId;
        this.periodFrom = periodFrom;
        this.periodTo = periodTo;
        this.revision = revision;
        this.status = InsightRunStatus.RUNNING;
    }

    /** 한 아이의 한 기간을 집는다. 저장이 UNIQUE 에 걸리면 다른 워커가 먼저 집은 것이다. */
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

    /** 끝났다. {@code count} 가 0 이어도 성공이다 — 낼 것이 없었다는 뜻이다. */
    public void finish(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("인사이트 수는 음수가 될 수 없습니다: " + count);
        }
        this.status = InsightRunStatus.DONE;
        this.insightCount = count;
    }

    /** 실패했다. 건수는 채우지 않는다 — 0 건으로 끝난 것과 구별돼야 한다. */
    public void fail() {
        this.status = InsightRunStatus.FAILED;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
