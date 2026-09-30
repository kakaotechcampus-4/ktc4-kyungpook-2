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
