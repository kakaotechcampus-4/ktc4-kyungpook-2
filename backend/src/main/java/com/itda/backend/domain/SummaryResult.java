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
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 요약 에이전트가 쓴 요약 한 편 (DB 스키마 §8.2).
 *
 * <p>아동 × 날짜 × 기관의 일지 N건을 묶은 것이다. 일지가 {@code journal_entry.summary_id} 로 이 요약을 가리킨다.
 * 다른 테이블은 FK·연관관계 없이 ID 컬럼으로만 가리킨다 (DB 스키마 §16).
 *
 * <p>승인 전에는 같은 묶음에 일지가 더 오면 같은 판을 덮어쓰고, 승인 뒤에는 새 판(revision + 1)으로 쌓는다.
 * 그래서 {@code updated_at} 은 있지만 {@code deleted_at} 은 없다.
 */
@Entity
@Table(name = "summary_result", uniqueConstraints = @UniqueConstraint(
        name = "uk_summary_result_group_revision",
        columnNames = {"child_id", "entry_date", "institution_id", "revision"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SummaryResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    // raw_record.institution_id 는 VARCHAR 지만 여기는 organization.id 그대로 BIGINT 다 (DB 스키마 §8.2, §10.2-10).
    @Column(name = "institution_id", nullable = false)
    private Long institutionId;

    @Column(nullable = false)
    private Integer revision;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    // AI 가 보낸 JSON 조각을 그대로 담는다. claims 는 [{text, evidence: [{journal_entry_id, quote, span}]}],
    // span 은 각 일지 원문 기준 Python 코드포인트 인덱스다.
    @Column(columnDefinition = "TEXT")
    private String claims;

    @Column(columnDefinition = "TEXT")
    private String coveredEntryIds;

    @Column(columnDefinition = "TEXT")
    private String uncoveredEntryIds;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SummaryStatus status;

    @Column(columnDefinition = "TEXT")
    private String rawResponse;

    // AI 응답(SummaryOutput)에 아직 모델 버전이 없어 비워 둔다.
    @Column(length = 100)
    private String modelVersion;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private SummaryResult(Long childId, LocalDate entryDate, Long institutionId, Integer revision, String content,
            String claims, String coveredEntryIds, String uncoveredEntryIds, String rawResponse) {
        this.childId = childId;
        this.entryDate = entryDate;
        this.institutionId = institutionId;
        this.revision = revision;
        this.content = content;
        this.claims = claims;
        this.coveredEntryIds = coveredEntryIds;
        this.uncoveredEntryIds = uncoveredEntryIds;
        this.rawResponse = rawResponse;
        this.status = SummaryStatus.GATE1_PENDING;
    }

    /**
     * 그 묶음의 새 판을 만든다. 첫 요약은 1, 승인된 판이 있으면 그다음 번호다 — 승인된 글은 고치지 않고 새로 쌓는다.
     */
    public static SummaryResult of(Long childId, LocalDate entryDate, Long institutionId, int revision,
            String content, String claims, String coveredEntryIds, String uncoveredEntryIds, String rawResponse) {
        if (childId == null || entryDate == null || institutionId == null) {
            throw new IllegalArgumentException("요약 묶음의 아동·날짜·기관은 필수입니다.");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("요약 판 번호는 1부터입니다: " + revision);
        }
        requireContent(content);
        return new SummaryResult(childId, entryDate, institutionId, revision, content, claims, coveredEntryIds,
                uncoveredEntryIds, rawResponse);
    }

    /** 승인 전이라 같은 묶음에 일지가 더 오면 덮어쓸 수 있는지. */
    public boolean isBeforeApproval() {
        return this.status == SummaryStatus.GENERATED || this.status == SummaryStatus.GATE1_PENDING;
    }

    /** 승인 전 요약을 같은 묶음의 새 결과로 덮어쓴다. 판 번호는 그대로다. */
    public void overwrite(String content, String claims, String coveredEntryIds, String uncoveredEntryIds,
            String rawResponse) {
        if (!isBeforeApproval()) {
            throw new IllegalStateException("승인 전 요약만 덮어쓸 수 있습니다: " + this.status);
        }
        requireContent(content);
        this.content = content;
        this.claims = claims;
        this.coveredEntryIds = coveredEntryIds;
        this.uncoveredEntryIds = uncoveredEntryIds;
        this.rawResponse = rawResponse;
    }

    private static void requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("요약 본문은 필수입니다.");
        }
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
