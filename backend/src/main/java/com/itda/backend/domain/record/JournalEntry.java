package com.itda.backend.domain.record;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.itda.backend.global.jpa.TimestampedEntity;

@Entity
@Table(name = "journal_entry", indexes = {
        @Index(name = "idx_journal_entry_status_id", columnList = "status, id"),
        @Index(name = "idx_journal_entry_raw_record_id", columnList = "raw_record_id"),
        @Index(name = "idx_journal_entry_summary_id", columnList = "summary_id"),
        @Index(name = "idx_journal_entry_child_id_entry_date", columnList = "child_id, entry_date")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JournalEntry extends TimestampedEntity {

    public static final int MAX_CONTENT_LENGTH = 5_000;

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

    @Column(name = "summary_id")
    private Long summaryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private JournalEntryStatus status;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "agent_retry_count")
    private Integer agentRetryCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_stage", length = 30)
    private FailureStage failureStage;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 30)
    private FailureReason failureReason;

    @Column(name = "revised_by", length = 50)
    private String revisedBy;

    @Column(name = "revised_at")
    private LocalDateTime revisedAt;

    private JournalEntry(Long rawRecordId, LocalDate entryDate, String content, Integer sequenceNo) {
        this.rawRecordId = rawRecordId;
        this.entryDate = entryDate;
        this.content = content;
        this.sequenceNo = sequenceNo;
        this.status = JournalEntryStatus.PENDING;
        this.agentRetryCount = 0;
    }

    public static JournalEntry of(Long rawRecordId, LocalDate entryDate, String content, Integer sequenceNo) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("일지 내용은 필수입니다.");
        }
        return new JournalEntry(rawRecordId, entryDate, content, sequenceNo);
    }

    public void assignChild(Long childId) {
        if (childId == null) {
            throw new IllegalArgumentException("확정할 아동 ID는 필수입니다.");
        }
        this.childId = childId;
    }

    public void startMatching() {
        requireStatus(JournalEntryStatus.PENDING);
        this.status = JournalEntryStatus.MATCHING;
    }

    public void confirmMatch(Long childId) {
        requireStatus(JournalEntryStatus.MATCHING);
        enterValidationQueue(childId);
    }

    public void requestMatchReview() {
        requireStatus(JournalEntryStatus.MATCHING);
        this.status = JournalEntryStatus.MATCH_REVIEW;
    }

    public void failMatchingBySystemError(FailureReason reason) {
        requireStatus(JournalEntryStatus.MATCHING);
        stopBySystemError(FailureStage.MATCHING, reason);
    }

    public boolean isSendableToAgent() {
        return this.content.length() <= MAX_CONTENT_LENGTH;
    }

    public void failMatching() {
        requireStatus(JournalEntryStatus.MATCHING);
        this.status = JournalEntryStatus.FAILED;
    }

    public void releaseMatching() {
        requireStatus(JournalEntryStatus.MATCHING);
        this.status = JournalEntryStatus.PENDING;
    }

    public void releaseMatchingAfterAgentDown() {
        releaseMatching();
        countAgentRetry();
    }

    public boolean isAwaitingReview() {
        return this.status == JournalEntryStatus.MATCH_REVIEW || this.status == JournalEntryStatus.FAILED;
    }

    public void confirmMatchByReviewer(Long childId) {
        requireAwaitingReview();
        enterValidationQueue(childId);
    }

    public void exclude() {
        requireAwaitingReview();
        this.status = JournalEntryStatus.EXCLUDED;
    }

    public void startValidating() {
        requireStatus(JournalEntryStatus.MATCHED);
        this.status = JournalEntryStatus.VALIDATING;
    }

    public void passValidation() {
        requireStatus(JournalEntryStatus.VALIDATING);
        this.status = JournalEntryStatus.VALIDATED;
    }

    public void blockValidation() {
        requireStatus(JournalEntryStatus.VALIDATING);
        this.status = JournalEntryStatus.VALIDATION_BLOCKED;
    }

    public void failValidation(FailureReason reason) {
        requireStatus(JournalEntryStatus.VALIDATING);
        stopBySystemError(FailureStage.VALIDATION, reason);
    }

    public boolean isAwaitingReinput() {
        return this.status == JournalEntryStatus.VALIDATION_BLOCKED;
    }

    public void revise(String newContent, String reviserId) {
        requireAwaitingReinput();
        if (this.childId == null) {
            throw new IllegalStateException("확정된 아동이 없는 일지입니다.");
        }
        if (newContent == null || newContent.isBlank()) {
            throw new IllegalArgumentException("고친 본문은 필수입니다.");
        }
        if (newContent.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("고친 본문은 " + MAX_CONTENT_LENGTH + "자까지입니다.");
        }
        if (hasSameContent(newContent)) {
            throw new IllegalArgumentException("본문이 고쳐지지 않았습니다.");
        }
        if (reviserId == null || reviserId.isBlank()) {
            throw new IllegalArgumentException("고친 사람은 필수입니다.");
        }
        this.content = newContent;
        this.revisedBy = reviserId;
        this.revisedAt = LocalDateTime.now();
        enterValidationQueue(this.childId);
    }

    public boolean hasSameContent(String newContent) {
        return newContent != null && newContent.strip().equals(this.content.strip());
    }

    public void holdAfterValidation() {
        requireAwaitingReinput();
        this.status = JournalEntryStatus.VALIDATION_HELD;
    }

    private void requireAwaitingReinput() {
        if (!isAwaitingReinput()) {
            throw new IllegalStateException("검증에서 막힌 기록이 아닙니다: " + this.status);
        }
    }

    public void releaseValidating() {
        requireStatus(JournalEntryStatus.VALIDATING);
        this.status = JournalEntryStatus.MATCHED;
    }

    public void releaseValidatingAfterAgentDown() {
        releaseValidating();
        countAgentRetry();
    }

    public int getAgentRetryCount() {
        return this.agentRetryCount == null ? 0 : this.agentRetryCount;
    }

    private void countAgentRetry() {
        this.agentRetryCount = getAgentRetryCount() + 1;
    }

    private void enterValidationQueue(Long childId) {
        assignChild(childId);
        this.status = JournalEntryStatus.MATCHED;
        this.agentRetryCount = 0;
    }

    public void startSummarizing() {
        if (this.status != JournalEntryStatus.VALIDATED && this.status != JournalEntryStatus.GATE1_PENDING) {
            throw new IllegalStateException("요약할 수 있는 상태가 아닙니다: " + this.status);
        }
        this.status = JournalEntryStatus.SUMMARIZING;
    }

    public void completeSummary(Long summaryId) {
        requireStatus(JournalEntryStatus.SUMMARIZING);
        if (summaryId == null) {
            throw new IllegalArgumentException("요약 ID는 필수입니다.");
        }
        this.summaryId = summaryId;
        this.status = JournalEntryStatus.GATE1_PENDING;
    }

    public void failSummary() {
        requireStatus(JournalEntryStatus.SUMMARIZING);
        this.status = this.summaryId != null ? JournalEntryStatus.GATE1_PENDING : JournalEntryStatus.SUMMARY_FAILED;
    }

    public void failSummaryBySystemError(FailureReason reason) {
        requireStatus(JournalEntryStatus.SUMMARIZING);
        requireFailureReason(reason);
        if (this.summaryId != null) {
            this.status = JournalEntryStatus.GATE1_PENDING;
            return;
        }
        stopBySystemError(FailureStage.SUMMARY, reason);
    }

    public void releaseSummarizing() {
        requireStatus(JournalEntryStatus.SUMMARIZING);
        this.status = this.summaryId != null ? JournalEntryStatus.GATE1_PENDING : JournalEntryStatus.VALIDATED;
    }

    public void retrySummary() {
        requireStatus(JournalEntryStatus.SUMMARY_FAILED);
        this.status = JournalEntryStatus.VALIDATED;
    }

    public void closeFailedSummary() {
        requireStatus(JournalEntryStatus.SUMMARY_FAILED);
        this.status = JournalEntryStatus.COMPLETED;
    }

    public void completeGate1() {
        requireStatus(JournalEntryStatus.GATE1_PENDING);
        this.status = JournalEntryStatus.COMPLETED;
    }

    public void revertToSummaryWait() {
        requireStatus(JournalEntryStatus.GATE1_PENDING);
        this.summaryId = null;
        this.status = JournalEntryStatus.VALIDATED;
    }

    public void holdGate1() {
        requireStatus(JournalEntryStatus.GATE1_PENDING);
        this.status = JournalEntryStatus.GATE1_HELD;
    }

    public void reopenGate1() {
        requireStatus(JournalEntryStatus.GATE1_HELD);
        this.status = JournalEntryStatus.GATE1_PENDING;
    }

    private void stopBySystemError(FailureStage stage, FailureReason reason) {
        requireFailureReason(reason);
        this.status = JournalEntryStatus.SYSTEM_ERROR;
        this.failureStage = stage;
        this.failureReason = reason;
    }

    private static void requireFailureReason(FailureReason reason) {
        if (reason == null) {
            throw new IllegalArgumentException("시스템 오류 원인은 필수입니다.");
        }
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
}
