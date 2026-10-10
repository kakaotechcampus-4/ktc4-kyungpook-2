package com.itda.backend.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 검증 에이전트 실행 결과 한 건 (DB 스키마 §8.1).
 *
 * <p>다른 테이블은 FK·연관관계 없이 ID 컬럼으로만 가리킨다 (DB 스키마 §16). 실행 이력이라 삭제하지 않고
 * 수정하지도 않는다 — 다시 검증하면 새 행을 쌓는다. 그래서 {@code updated_at}·{@code deleted_at} 이 없다.
 */
@Entity
@Table(name = "validation_result")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ValidationResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "journal_entry_id", nullable = false)
    private Long journalEntryId;

    // 입력으로 쓴 매칭 결과. 일지는 journalEntryId 로 직접 찾는다 — 검증이 매칭 결과에 과하게 기대지 않도록.
    @Column(name = "matching_result_id")
    private Long matchingResultId;

    // 검증 대상 아동 (요청의 subject_child_id).
    @Column(name = "child_id")
    private Long childId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ValidationVerdict verdict;

    // AI 가 보낸 JSON 조각을 그대로 담는다. issue_types 는 한글 문자열 배열 ["개인정보표현"],
    // evidence 는 [{start, end}] (Python 코드포인트 인덱스 — 화면에서는 Array.from 으로 잘라야 한다).
    @Column(columnDefinition = "TEXT")
    private String issueTypes;

    @Column(columnDefinition = "TEXT")
    private String evidence;

    @Column(columnDefinition = "TEXT")
    private String rawResponse;

    @Column(nullable = false)
    private LocalDateTime createdAt;

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

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
