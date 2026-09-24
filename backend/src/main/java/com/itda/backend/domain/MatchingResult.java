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

    // status=MULTI일 때만 채워진다 (DB수정본 §7.1).
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private MultiReason multiReason;

    // 표지 힌트(hint_name/hint_birthdate)와 다른 아동으로 판단했는지.
    @Column
    private Boolean hintMismatch;

    // ponytail: DB수정본(9/23) ERD 기준 JSON 컬럼 — JSON 라이브러리/컨버터가 아직 없어서
    // 원문 JSON 텍스트를 그대로 담는다. AI가 직접 이 테이블에 쓸 때도 같은 형식이면 된다.
    // candidates: AI/matching/schemas.py의 Candidate[] ({child_id, confidence})
    @Column(columnDefinition = "TEXT")
    private String candidates;

    // evidence: AI/matching/schemas.py의 EvidenceSpan[] ({start, end})
    @Column(columnDefinition = "TEXT")
    private String evidence;

    // rawResponse: Matching Agent 응답 원문 전체 (디버깅/재처리용, 기존 reason 컬럼 대체)
    @Column(columnDefinition = "TEXT")
    private String rawResponse;

    @Column(length = 100)
    private String modelVersion;

    // 팀원 리뷰 반영: null이면 AI가 그대로 확정한 것, 값이 있으면 사람이 큐에서 처리한
    // 것 — status만으로는 AI 자동확정과 사람이 처리한 건(둘 다 AUTO)을 구분 못 해서 추가.
    // User 도메인이 아직 없어서 우선 카카오ID(RawRecordController와 같은 패턴)를 담는다.
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
        this.rawResponse = rawResponse;
        this.modelVersion = modelVersion;
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    // 선생님이 확인 필요 큐에서 아이를 직접 확정한 경우.
    // ponytail: AI 계약(MatchStatus)에는 "사람이 확정함"을 뜻하는 별도 값이 없어서,
    // AUTO를 "더 이상 검토가 필요 없다"는 의미로 재사용한다 — AI가 자동 확정했든
    // 사람이 확정했든 확인 필요 큐(findByStatusNot(AUTO))에서는 빠져야 하기 때문.
    // reviewerId를 함께 남겨서 "누가" 확정했는지(=사람이 처리했다는 사실 자체)는
    // status와 별개로 감사 추적이 가능하게 한다.
    public void resolveAsAssigned(Long childId, String reviewerId) {
        this.matchedChildId = childId;
        this.status = MatchingStatus.AUTO;
        this.reviewerId = reviewerId;
        this.updatedAt = LocalDateTime.now();
    }

    // 선생님이 "우리 기관 아동 아님"으로 제외한 경우.
    // 버그 수정: UNMATCHED는 AI가 내놓는 "아직 검토 필요" 상태와 같은 값이라, 여기 쓰면
    // findByStatusNot(AUTO) 큐에서 이 레코드가 절대 안 빠진다. resolveAsAssigned와 마찬가지로
    // "사람이 처리를 끝냈다"는 뜻으로 AUTO를 쓰고, reviewerId로 AI 자동확정과 구분한다.
    public void resolveAsNotOurs(String reviewerId) {
        this.matchedChildId = null;
        this.status = MatchingStatus.AUTO;
        this.reviewerId = reviewerId;
        this.updatedAt = LocalDateTime.now();
    }
}
