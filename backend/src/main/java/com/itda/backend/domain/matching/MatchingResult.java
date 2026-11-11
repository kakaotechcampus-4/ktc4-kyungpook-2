package com.itda.backend.domain.matching;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.user.User;

import java.math.BigDecimal;
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

import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "matching_result",
        indexes = @Index(name = "idx_matching_result_journal_entry_id", columnList = "journal_entry_id"))
@Getter
@NoArgsConstructor
public class MatchingResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long journalEntryId;

    @Column
    private Long matchedChildId;

    @Column(precision = 5, scale = 4)
    private BigDecimal confidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MatchingStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private MultiReason multiReason;

    @Column
    private Boolean hintMismatch;

    @Column(columnDefinition = "TEXT")
    private String candidates;

    @Column(columnDefinition = "TEXT")
    private String evidence;

    @Column(columnDefinition = "TEXT")
    private String mentionedChildIds;

    @Column(columnDefinition = "TEXT")
    private String rawResponse;

    @Column(length = 100)
    private String modelVersion;

    @Column(length = 255)
    private String reviewerId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public MatchingResult(
            Long journalEntryId,
            Long matchedChildId,
            BigDecimal confidence,
            MatchingStatus status,
            MultiReason multiReason,
            Boolean hintMismatch,
            String candidates,
            String evidence,
            String mentionedChildIds,
            String rawResponse,
            String modelVersion) {
        this.journalEntryId = journalEntryId;
        this.matchedChildId = matchedChildId;
        this.confidence = confidence;
        this.status = status;
        this.multiReason = multiReason;
        this.hintMismatch = hintMismatch;
        this.candidates = candidates;
        this.evidence = evidence;
        this.mentionedChildIds = mentionedChildIds;
        this.rawResponse = rawResponse;
        this.modelVersion = modelVersion;
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static MatchingResult failed(Long journalEntryId) {
        return new MatchingResult(journalEntryId, null, null, MatchingStatus.FAILED,
                null, null, null, null, null, null, null);
    }

    public void resolveAsAssigned(Long childId, String reviewerId) {
        this.matchedChildId = childId;
        this.status = MatchingStatus.AUTO;
        this.reviewerId = reviewerId;
        this.updatedAt = LocalDateTime.now();
    }

    public void resolveAsNotOurs(String reviewerId) {
        this.matchedChildId = null;
        this.status = MatchingStatus.AUTO;
        this.reviewerId = reviewerId;
        this.updatedAt = LocalDateTime.now();
    }
}
