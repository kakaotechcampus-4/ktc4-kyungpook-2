package com.itda.backend.domain.insight;

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

    /** 같은 아동·같은 기간의 몇 번째 실행인지. 1 부터. {@code insight_result.revision} 이 이 값을 받아 쓴다. */
    @Column(nullable = false)
    private Integer revision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InsightRunStatus status;

    /** 낸 인사이트 수. {@code DONE} 일 때만 채운다. <b>0 도 정상이다.</b> */
    @Column(name = "insight_count")
    private Integer insightCount;

    /**
     * 그 실행이 본 {@code child_context} 중 가장 큰 id — <b>어디까지 봤나</b>.
     *
     * <p>Gate 1 승인은 교사가 틈틈이 해서 한 주치가 한 번에 올라오지 않는다. "이 기간을 처리했다" 만
     * 기억하면 뒤에 승인된 기록이 영영 빠진다. {@code child_context} 는 지우지 않고 쌓이기만 해(§9.2)
     * id 가 단조 증가하므로, 이 값 하나로 재료가 늘었는지 알 수 있다.
     *
     * <p>{@code null} 은 "눈금을 모른다" 다 — 이 컬럼이 생기기 전 행이 그렇다. 그때는 다시 집는다.
     */
    @Column(name = "last_context_id")
    private Long lastContextId;

    /**
     * 왜 실패했나. {@code FAILED} 일 때만 채운다. AI 가 503 body 에 실은 {@code detail.reason} 과
     * {@code detail.failure} 를 {@code llm_unavailable:empty_response} 처럼 붙여 남긴다 — AI {@code /metrics}
     * 와 같은 모양이라 양쪽을 같은 말로 찾을 수 있다. 값 목록은 §9.5.
     *
     * <p>enum 이 아니라 문자열이다. AI 가 종류를 늘려도 BE 를 고치지 않고 그대로 남기려는 것이고,
     * enum 이면 값이 늘 때마다 CHECK 제약 수동 조치(§11.4)가 따라온다.
     */
    @Column(name = "failure_reason", length = FAILURE_REASON_LENGTH)
    private String failureReason;

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

    /**
     * 끝났다. {@code count} 가 0 이어도 성공이다 — 낼 것이 없었다는 뜻이다.
     *
     * @param lastContextId 이번에 본 {@code child_context} 중 가장 큰 id. 다음 차례가 이 값과 견줘
     *                      재료가 늘었는지 본다. 재료가 아예 없었으면 {@code null}
     */
    public void finish(int count, Long lastContextId) {
        if (count < 0) {
            throw new IllegalArgumentException("인사이트 수는 음수가 될 수 없습니다: " + count);
        }
        this.status = InsightRunStatus.DONE;
        this.insightCount = count;
        this.lastContextId = lastContextId;
    }

    /** 실패했다. 건수는 채우지 않는다 — 0 건으로 끝난 것과 구별돼야 한다. */
    public void fail(String reason) {
        this.status = InsightRunStatus.FAILED;
        // AI 가 낯선 긴 값을 보내도 저장이 터지지 않게 자른다. 터지면 그 실행이 RUNNING 으로 남아 다시 켤 때까지 안 집힌다.
        this.failureReason = reason == null || reason.length() <= FAILURE_REASON_LENGTH
                ? reason : reason.substring(0, FAILURE_REASON_LENGTH);
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
