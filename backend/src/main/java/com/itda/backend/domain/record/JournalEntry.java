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

/**
 * 원본 파일 안의 관찰 기록 한 건. 매칭·검증의 처리 단위다.
 *
 * <p>기관 컬럼이 없다 — 어느 기관의 일지인지는 {@code rawRecordId} 로 {@code raw_record} 를 거쳐 찾는다.
 *
 * <p>{@code rawRecordId} 는 플랫폼 직접 입력(원본 파일 없음)이라 NULL 일 수 있고,
 * {@code childId} 는 매칭이 확정되기 전까지 NULL 이다 (DB 스키마 §6.2).
 */
@Entity
@Table(name = "journal_entry", indexes = {
        // 매칭 · 검증 · 요약 워커가 5초마다 상태로 대상을 찾는다 (#479)
        @Index(name = "idx_journal_entry_status_id", columnList = "status, id"),
        // 처리 현황 · 기관 조건 조인은 원본 파일로 잇는다
        @Index(name = "idx_journal_entry_raw_record_id", columnList = "raw_record_id"),
        @Index(name = "idx_journal_entry_summary_id", columnList = "summary_id"),
        // 요약 묶음(아동 · 날짜)
        @Index(name = "idx_journal_entry_child_id_entry_date", columnList = "child_id, entry_date")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JournalEntry extends TimestampedEntity {

    /**
     * AI 에 보낼 수 있는 일지 본문의 최대 글자 수 (#424). 업로드는 이보다 긴 일지가 든 파일을 거절하고, 본문 고치기도 이 길이까지만
     * 받는다. 상한이 생기기 전에 올라온 긴 일지는 각 에이전트 요청을 만들기 전에 시스템 오류({@link FailureReason#CONTENT_TOO_LONG})로
     * 멈춘다 — 잘라 보내지 않는다. 화면(frontend {@code MAX_REVISED_CONTENT})과 같은 값이어야 해서 설정이 아니라 상수다.
     */
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

    // 어느 요약에 들어갔는지. 요약은 일지 N건을 묶으므로 요약이 아니라 일지가 가리킨다 (DB 스키마 §8.2).
    @Column(name = "summary_id")
    private Long summaryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private JournalEntryStatus status;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // AI 다운으로 지금 단계(매칭 또는 검증)를 되돌린 횟수 (#153). 배포 DB 에 행이 있어 NOT NULL 로 추가할 수 없어서
    // NULL 을 허용하고 0 으로 읽는다 (DB 스키마 §11.4). 매칭 대기(PENDING)는 0 으로 만들어지고, 검증 대기(MATCHED)로
    // 들어올 때 0 으로 돌린다. 매칭·검증 중이 아닌 상태에 남은 값은 뜻이 없다 — 실패 사유는 #180 이 따로 남긴다.
    @Column(name = "agent_retry_count")
    private Integer agentRetryCount;

    // 시스템 오류(SYSTEM_ERROR)로 멈춘 단계와 원인 (#180). 그 상태일 때만 채우고 다른 상태에서는 비어 있다.
    // 고친 뒤 되돌리는 SQL 이 두 값으로 고르고 함께 비운다 (backend/scripts/recover-system-errors.sql).
    @Enumerated(EnumType.STRING)
    @Column(name = "failure_stage", length = 30)
    private FailureStage failureStage;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 30)
    private FailureReason failureReason;

    // 검증에서 막힌 본문을 선생님이 수정 요청 큐에서 마지막으로 고친 사람(user id)과 시각 (#357). 고친 적 없으면 비어 있다.
    // 이전 본문은 따로 남기지 않는다 — 처음 올라온 글은 원본 파일(raw_record)에 그대로 있다.
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
        enterValidationQueue(childId);
    }

    /** AI가 확정하지 못했다(review·multi·unmatched). 어떤 경우인지는 matching_result.status 가 가진다. */
    public void requestMatchReview() {
        requireStatus(JournalEntryStatus.MATCHING);
        this.status = JournalEntryStatus.MATCH_REVIEW;
    }

    /**
     * 매칭 에이전트에 보낼 수 없는 일지다 (#424, 지금은 본문이 너무 길 때뿐). 확인 필요 큐({@code FAILED})로 보내지 않는다 — 선생님이
     * 아이를 골라도 검증에서 같은 이유로 다시 멈춘다. 시스템 오류로 멈추고 원인을 남긴다.
     */
    public void failMatchingBySystemError(FailureReason reason) {
        requireStatus(JournalEntryStatus.MATCHING);
        stopBySystemError(FailureStage.MATCHING, reason);
    }

    /** AI 에 보낼 수 있는 길이인지 ({@link #MAX_CONTENT_LENGTH}). */
    public boolean isSendableToAgent() {
        return this.content.length() <= MAX_CONTENT_LENGTH;
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

    /** AI 가 다운이라 매칭하지 못했다. 일지 잘못이 아니므로 실패로 남기지 않고 대기로 되돌리고 횟수를 센다. */
    public void releaseMatchingAfterAgentDown() {
        releaseMatching();
        countAgentRetry();
    }

    /** 사람이 확인해야 하는 상태인지 — AI가 확정하지 못했거나(MATCH_REVIEW) 호출이 실패했다(FAILED). */
    public boolean isAwaitingReview() {
        return this.status == JournalEntryStatus.MATCH_REVIEW || this.status == JournalEntryStatus.FAILED;
    }

    /** 선생님이 확인 필요 큐에서 아동을 골랐다. AI 자동 확정과 같은 상태로 보내 검증 단계가 이어 가져가게 한다. */
    public void confirmMatchByReviewer(Long childId) {
        requireAwaitingReview();
        enterValidationQueue(childId);
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

    /** 검증 호출이 실패했거나 결과를 남길 수 없다. 다시 돌려도 같으므로 시스템 오류로 멈추고 원인을 남긴다. */
    public void failValidation(FailureReason reason) {
        requireStatus(JournalEntryStatus.VALIDATING);
        stopBySystemError(FailureStage.VALIDATION, reason);
    }

    /** 검증에서 막힌(BLOCK) 기록을 선생님이 수정 요청 큐에서 처리할 수 있는 상태인지. */
    public boolean isAwaitingReinput() {
        return this.status == JournalEntryStatus.VALIDATION_BLOCKED;
    }

    /**
     * 선생님이 막힌 본문을 수정 요청 큐에서 고쳤다(#357). 고친 본문으로 검증을 다시 받도록 검증 대기로 보내고, 아동은 그대로 둔다.
     * 고치지 않은 본문(앞뒤 공백만 다른 글 포함)은 거절한다 — 실수로 그대로 저장하는 것과, 같은 글을 그대로 다시 돌려 판정이
     * 바뀌길 기다리는 가장 쉬운 우회를 막는다. 한 글자만 바꾸는 우회까지 막지는 못한다(고친 사람이 남는다).
     */
    public void revise(String newContent, String reviserId) {
        requireAwaitingReinput();
        if (this.childId == null) {
            // 검증은 아동이 정해진 일지만 한다. 비어 있으면 데이터가 망가진 것이라 입력 오류와 구분한다.
            throw new IllegalStateException("확정된 아동이 없는 일지입니다.");
        }
        if (newContent == null || newContent.isBlank()) {
            throw new IllegalArgumentException("고친 본문은 필수입니다.");
        }
        if (newContent.length() > MAX_CONTENT_LENGTH) {
            // 요청 검증(@Size)을 거치지 않은 호출도 막는다 — 고친 본문은 그대로 검증 AI 에 간다 (#424).
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

    /** 고친 본문이 지금 본문과 같은지. 앞뒤 공백만 다른 것도 같다고 본다 — {@link #revise} 가 거절하는 기준이다. */
    public boolean hasSameContent(String newContent) {
        return newContent != null && newContent.strip().equals(this.content.strip());
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

    /** AI 가 다운이라 검증하지 못했다. 일지 잘못이 아니므로 실패로 남기지 않고 검증 대기로 되돌리고 횟수를 센다. */
    public void releaseValidatingAfterAgentDown() {
        releaseValidating();
        countAgentRetry();
    }

    /** AI 다운으로 지금 단계를 되돌린 횟수. 컬럼이 비어 있는(추가 전부터 있던) 행은 0 이다. */
    public int getAgentRetryCount() {
        return this.agentRetryCount == null ? 0 : this.agentRetryCount;
    }

    private void countAgentRetry() {
        this.agentRetryCount = getAgentRetryCount() + 1;
    }

    /**
     * 매칭이 확정돼 검증 대기로 들어간다. AI 다운으로 되돌린 횟수를 0 으로 돌린다 — 매칭에서 쓴 횟수가 넘어가면 검증은
     * 시작하자마자 상한에 걸린다. 검증 대기로 들어오는 새 경로는 이 메서드를 쓴다 (검증 중 되돌리기는 제외).
     */
    private void enterValidationQueue(Long childId) {
        assignChild(childId);
        this.status = JournalEntryStatus.MATCHED;
        this.agentRetryCount = 0;
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
     * 근거가 남은 문장이 없어 요약하지 못했다. 다시 요약하면 될 수 있다. 이미 승인 전 요약에 들어가 있던 일지는 그 요약이
     * 그대로 유효하므로 Gate 1 대기로 돌리고, 처음 요약하던 일지만 요약 실패로 남긴다.
     */
    public void failSummary() {
        requireStatus(JournalEntryStatus.SUMMARIZING);
        this.status = this.summaryId != null ? JournalEntryStatus.GATE1_PENDING : JournalEntryStatus.SUMMARY_FAILED;
    }

    /**
     * 요약 호출이 실패했거나 결과를 남길 수 없다. 다시 돌려도 같으므로 처음 요약하던 일지는 시스템 오류로 멈추고 원인을
     * 남긴다. 이미 승인 전 요약에 들어가 있던 일지는 {@link #failSummary()} 와 같이 Gate 1 대기로 돌린다.
     */
    public void failSummaryBySystemError(FailureReason reason) {
        requireStatus(JournalEntryStatus.SUMMARIZING);
        requireFailureReason(reason);
        if (this.summaryId != null) {
            this.status = JournalEntryStatus.GATE1_PENDING;
            return;
        }
        stopBySystemError(FailureStage.SUMMARY, reason);
    }

    /**
     * 요약 중인 일지를 요약하기 전 상태로 돌린다. 처리하던 앱이 꺼졌거나, AI 가 응답하지 않거나, 집은 뒤 그 기관 동의가
     * 철회돼 요약하지 않기로 했을 때다.
     */
    public void releaseSummarizing() {
        requireStatus(JournalEntryStatus.SUMMARIZING);
        this.status = this.summaryId != null ? JournalEntryStatus.GATE1_PENDING : JournalEntryStatus.VALIDATED;
    }

    /**
     * 교사가 검토에서 요약 실패 일지를 [다시 요약]했다 (#369). 요약 전으로 돌아가 워커가 다시 집는다. 횟수 제한은 없다 —
     * 또 실패하면 다시 요약 실패로 검토에 뜬다.
     */
    public void retrySummary() {
        requireStatus(JournalEntryStatus.SUMMARY_FAILED);
        this.status = JournalEntryStatus.VALIDATED;
    }

    /**
     * 교사가 검토에서 요약 실패 일지를 [요약 없이 마무리]했다 (#369). 요약할 내용 없음 확인(#244)처럼 끝내되, 가리킬
     * 요약이 없어 요약 행은 만들지 않는다. 아동 맥락·보호자·인사이트로 가지 않는다.
     */
    public void closeFailedSummary() {
        requireStatus(JournalEntryStatus.SUMMARY_FAILED);
        this.status = JournalEntryStatus.COMPLETED;
    }

    /** 이 일지가 든 요약이 승인됐다. 다시 요약 대상이 되지 않는다. */
    public void completeGate1() {
        requireStatus(JournalEntryStatus.GATE1_PENDING);
        this.status = JournalEntryStatus.COMPLETED;
    }

    /**
     * 이 일지가 든 요약할 내용 없음 요약을 교사가 되돌렸다. 요약 전으로 돌아가 [요약]을 다시 누르면 같은 묶음의 새 일지와
     * 함께 요약된다. 요약과의 연결도 끊는다 — 남아 있으면 다시 요약하다 실패할 때 "이미 Gate 1 에 있던 일지"로 보여
     * 끝난 요약을 가리키는 Gate 1 대기로 간다 ({@link #failSummary()}).
     */
    public void revertToSummaryWait() {
        requireStatus(JournalEntryStatus.GATE1_PENDING);
        this.summaryId = null;
        this.status = JournalEntryStatus.VALIDATED;
    }

    /** 이 일지가 든 요약이 보류됐다. 같은 묶음에 새 일지가 와도 함께 묶이지 않는다. */
    public void holdGate1() {
        requireStatus(JournalEntryStatus.GATE1_PENDING);
        this.status = JournalEntryStatus.GATE1_HELD;
    }

    /** 이 일지가 든 요약의 보류가 해제됐다. 같은 요약을 가리킨 채 Gate 1 대기로 돌아온다. */
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
