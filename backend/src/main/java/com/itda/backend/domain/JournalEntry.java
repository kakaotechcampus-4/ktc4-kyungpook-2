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

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 원본 파일 안의 관찰 기록 한 건. 매칭·검증의 처리 단위다.
 *
 * <p>기관 컬럼이 없다 — 어느 기관의 일지인지는 {@code rawRecordId} 로 {@code raw_record} 를 거쳐 찾는다.
 *
 * <p>{@code rawRecordId} 는 플랫폼 직접 입력(원본 파일 없음)이라 NULL 일 수 있고,
 * {@code childId} 는 매칭이 확정되기 전까지 NULL 이다 (DB 스키마 §6.2).
 */
@Entity
@Table(name = "journal_entry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "raw_record_id")
    private Long rawRecordId;

    private LocalDate entryDate;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    private Integer sequenceNo;

    @Column(name = "child_id")
    private Long childId;

    // 어느 요약에 들어갔는지. 요약은 일지 N건을 묶으므로 요약이 아니라 일지가 가리킨다 (DB 스키마 §8.2).
    @Column(name = "summary_id")
    private Long summaryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private JournalEntryStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    private JournalEntry(Long rawRecordId, LocalDate entryDate, String content, Integer sequenceNo) {
        this.rawRecordId = rawRecordId;
        this.entryDate = entryDate;
        this.content = content;
        this.sequenceNo = sequenceNo;
        this.status = JournalEntryStatus.PENDING;
    }

    public static JournalEntry of(Long rawRecordId, LocalDate entryDate, String content, Integer sequenceNo) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("일지 내용은 필수입니다.");
        }
        return new JournalEntry(rawRecordId, entryDate, content, sequenceNo);
    }

    /** 매칭이 확정되면 누구의 기록인지 채운다. 아동의 존재·소속 검증은 호출하는 Service 책임이다. */
    public void assignChild(Long childId) {
        if (childId == null) {
            throw new IllegalArgumentException("확정할 아동 ID는 필수입니다.");
        }
        this.childId = childId;
    }

    /** 워커가 매칭 대상으로 집어 간다. 다른 워커 실행이 같은 일지를 다시 집지 않도록 대기 상태에서만 허용한다. */
    public void startMatching() {
        requireStatus(JournalEntryStatus.PENDING);
        this.status = JournalEntryStatus.MATCHING;
    }

    /** AI가 자동 확정(auto)했다. 아동의 존재·소속 검증은 호출하는 Service 책임이다. */
    public void confirmMatch(Long childId) {
        requireStatus(JournalEntryStatus.MATCHING);
        assignChild(childId);
        this.status = JournalEntryStatus.MATCHED;
    }

    /** AI가 확정하지 못했다(review·multi·unmatched). 어떤 경우인지는 matching_result.status 가 가진다. */
    public void requestMatchReview() {
        requireStatus(JournalEntryStatus.MATCHING);
        this.status = JournalEntryStatus.MATCH_REVIEW;
    }

    /** AI 호출 자체가 실패했다. */
    public void failMatching() {
        requireStatus(JournalEntryStatus.MATCHING);
        this.status = JournalEntryStatus.FAILED;
    }

    /** 처리하던 앱이 꺼져서 매칭 중에 멈춘 일지를 다시 대기열로 돌린다. */
    public void releaseMatching() {
        requireStatus(JournalEntryStatus.MATCHING);
        this.status = JournalEntryStatus.PENDING;
    }

    /** 사람이 확인해야 하는 상태인지 — AI가 확정하지 못했거나(MATCH_REVIEW) 호출이 실패했다(FAILED). */
    public boolean isAwaitingReview() {
        return this.status == JournalEntryStatus.MATCH_REVIEW || this.status == JournalEntryStatus.FAILED;
    }

    /** 선생님이 확인 필요 큐에서 아동을 골랐다. AI 자동 확정과 같은 상태로 보내 검증 단계가 이어 가져가게 한다. */
    public void confirmMatchByReviewer(Long childId) {
        requireAwaitingReview();
        assignChild(childId);
        this.status = JournalEntryStatus.MATCHED;
    }

    /** 선생님이 확인 필요 큐에서 제외했다. 아동 기록으로 쓰지 않고 여기서 끝낸다. */
    public void exclude() {
        requireAwaitingReview();
        this.status = JournalEntryStatus.EXCLUDED;
    }

    /** 워커가 검증 대상으로 집어 간다. 매칭으로 아동이 확정된 일지에서만 허용한다. */
    public void startValidating() {
        requireStatus(JournalEntryStatus.MATCHED);
        this.status = JournalEntryStatus.VALIDATING;
    }

    /** 검증 결과가 PASS·REVIEW 다. 요약 단계가 이어 가져간다. */
    public void passValidation() {
        requireStatus(JournalEntryStatus.VALIDATING);
        this.status = JournalEntryStatus.VALIDATED;
    }

    /** 검증 결과가 BLOCK 이다. 개인정보가 든 기록이 요약으로 새지 않도록 여기서 멈춘다. */
    public void blockValidation() {
        requireStatus(JournalEntryStatus.VALIDATING);
        this.status = JournalEntryStatus.VALIDATION_BLOCKED;
    }

    /** 검증 호출 자체가 실패했다. */
    public void failValidation() {
        requireStatus(JournalEntryStatus.VALIDATING);
        this.status = JournalEntryStatus.FAILED;
    }

    /** 검증에서 막힌(BLOCK) 기록을 선생님이 수정 요청 큐에서 처리할 수 있는 상태인지. */
    public boolean isAwaitingReinput() {
        return this.status == JournalEntryStatus.VALIDATION_BLOCKED;
    }

    /**
     * 선생님이 원본을 고쳐 다시 올리기로 했다(#122). 원본은 수정하지 않고 새 파일로 올라오므로
     * 이 일지는 대체될 예정이고 여기서 끝난다.
     */
    public void requestReupload() {
        requireAwaitingReinput();
        this.status = JournalEntryStatus.REUPLOAD_REQUESTED;
    }

    /** 선생님이 이 기록을 보류했다(#122). 다음 단계로 가지 않는다. */
    public void holdAfterValidation() {
        requireAwaitingReinput();
        this.status = JournalEntryStatus.VALIDATION_HELD;
    }

    private void requireAwaitingReinput() {
        if (!isAwaitingReinput()) {
            throw new IllegalStateException("검증에서 막힌 기록이 아닙니다: " + this.status);
        }
    }

    /** 처리하던 앱이 꺼져서 검증 중에 멈춘 일지를 다시 검증 대기로 돌린다. 확정된 아동은 그대로 둔다. */
    public void releaseValidating() {
        requireStatus(JournalEntryStatus.VALIDATING);
        this.status = JournalEntryStatus.MATCHED;
    }

    /**
     * 워커가 요약 대상으로 집어 간다. 검증을 통과한 일지와, 승인 전 요약에 이미 들어간 일지(같은 묶음에 새 일지가
     * 와서 다시 요약한다)에서 허용한다. 승인 전 요약은 같은 판을 덮어쓴다 (DB 스키마 §8.2).
     */
    public void startSummarizing() {
        if (this.status != JournalEntryStatus.VALIDATED && this.status != JournalEntryStatus.GATE1_PENDING) {
            throw new IllegalStateException("요약할 수 있는 상태가 아닙니다: " + this.status);
        }
        this.status = JournalEntryStatus.SUMMARIZING;
    }

    /** 요약이 저장됐다. 요약에 반영되지 않은 일지(uncovered)도 같은 요약을 가리키고 함께 Gate 1 을 기다린다. */
    public void completeSummary(Long summaryId) {
        requireStatus(JournalEntryStatus.SUMMARIZING);
        if (summaryId == null) {
            throw new IllegalArgumentException("요약 ID는 필수입니다.");
        }
        this.summaryId = summaryId;
        this.status = JournalEntryStatus.GATE1_PENDING;
    }

    /**
     * 요약 호출이 실패했다. 이미 승인 전 요약에 들어가 있던 일지는 그 요약이 그대로 유효하므로 Gate 1 대기로 돌리고,
     * 처음 요약하던 일지만 실패로 남긴다.
     */
    public void failSummary() {
        requireStatus(JournalEntryStatus.SUMMARIZING);
        this.status = this.summaryId != null ? JournalEntryStatus.GATE1_PENDING : JournalEntryStatus.FAILED;
    }

    /** 처리하던 앱이 꺼져서 요약 중에 멈춘 일지를 요약하기 전 상태로 돌린다. */
    public void releaseSummarizing() {
        requireStatus(JournalEntryStatus.SUMMARIZING);
        this.status = this.summaryId != null ? JournalEntryStatus.GATE1_PENDING : JournalEntryStatus.VALIDATED;
    }

    private void requireAwaitingReview() {
        if (!isAwaitingReview()) {
            throw new IllegalStateException("사람 확인 대기 상태가 아닙니다: " + this.status);
        }
    }

    private void requireStatus(JournalEntryStatus expected) {
        if (this.status != expected) {
            throw new IllegalStateException("일지 상태가 " + expected + "가 아닙니다: " + this.status);
        }
    }

    public void delete() {
        this.deletedAt = LocalDateTime.now();
    }

    public void restore() {
        this.deletedAt = null;
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
