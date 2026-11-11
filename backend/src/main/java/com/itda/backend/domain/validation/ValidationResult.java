package com.itda.backend.domain.validation;

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
import com.itda.backend.global.jpa.CreatedAtEntity;

@Entity
@Table(name = "validation_result",
        indexes = @Index(name = "idx_validation_result_journal_entry_id", columnList = "journal_entry_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ValidationResult extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "journal_entry_id", nullable = false)
    private Long journalEntryId;

    @Column(name = "matching_result_id")
    private Long matchingResultId;

    @Column(name = "child_id")
    private Long childId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ValidationVerdict verdict;

    @Column(columnDefinition = "TEXT")
    private String issueTypes;

    @Column(columnDefinition = "TEXT")
    private String evidence;

    @Column(columnDefinition = "TEXT")
    private String rawResponse;

    private ValidationResult(Long journalEntryId, Long matchingResultId, Long childId, ValidationVerdict verdict,
            String issueTypes, String evidence, String rawResponse) {
        this.journalEntryId = journalEntryId;
        this.matchingResultId = matchingResultId;
        this.childId = childId;
        this.verdict = verdict;
        this.issueTypes = issueTypes;
        this.evidence = evidence;
        this.rawResponse = rawResponse;
    }

    public static ValidationResult of(Long journalEntryId, Long matchingResultId, Long childId,
            ValidationVerdict verdict, String issueTypes, String evidence, String rawResponse) {
        if (journalEntryId == null) {
            throw new IllegalArgumentException("검증한 일지 ID는 필수입니다.");
        }
        if (verdict == null) {
            throw new IllegalArgumentException("검증 판정은 필수입니다.");
        }
        return new ValidationResult(journalEntryId, matchingResultId, childId, verdict, issueTypes, evidence,
                rawResponse);
    }
}
