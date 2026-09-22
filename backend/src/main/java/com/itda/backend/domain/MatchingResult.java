package com.itda.backend.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;

// ponytail: DB초안 노션 문서 §16 원칙 — journal_entry_id/matched_child_id는 FK/연관관계가 아니라
// 참조용 Long 컬럼일 뿐이다. JournalEntry/Child 엔티티가 존재하지 않아도 이 엔티티는 컴파일/저장 가능해야 한다.
@Entity
@Table(name = "matching_result")
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

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(length = 100)
    private String modelVersion;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public MatchingResult(
            Long journalEntryId,
            Long matchedChildId,
            BigDecimal confidence,
            MatchingStatus status,
            String reason,
            String modelVersion) {
        this.journalEntryId = journalEntryId;
        this.matchedChildId = matchedChildId;
        this.confidence = confidence;
        this.status = status;
        this.reason = reason;
        this.modelVersion = modelVersion;
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    // 선생님이 확인 필요 큐에서 아이를 직접 확정한 경우.
    // ponytail: AI 계약(MatchStatus)에는 "사람이 확정함"을 뜻하는 별도 값이 없어서,
    // AUTO를 "더 이상 검토가 필요 없다"는 의미로 재사용한다 — AI가 자동 확정했든
    // 사람이 확정했든 확인 필요 큐(findByStatusNot(AUTO))에서는 빠져야 하기 때문.
    public void resolveAsAssigned(Long childId) {
        this.matchedChildId = childId;
        this.status = MatchingStatus.AUTO;
        this.updatedAt = LocalDateTime.now();
    }

    // 선생님이 "우리 기관 아동 아님"으로 제외한 경우.
    public void resolveAsNotOurs() {
        this.matchedChildId = null;
        this.status = MatchingStatus.UNMATCHED;
        this.updatedAt = LocalDateTime.now();
    }
}
