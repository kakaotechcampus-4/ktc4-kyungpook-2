package com.itda.backend.domain.summary;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.ColumnDefault;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.itda.backend.global.jpa.TimestampedEntity;

@Entity
@Table(name = "summary_result", uniqueConstraints = @UniqueConstraint(
        name = "uk_summary_result_group_revision",
        columnNames = {"child_id", "entry_date", "institution_id", "revision"}),
        indexes = @Index(name = "idx_summary_result_institution_id_status", columnList = "institution_id, status"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SummaryResult extends TimestampedEntity {

    private static final String EMPTY_ARRAY = "[]";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(name = "institution_id", nullable = false)
    private Long institutionId;

    @Column(nullable = false)
    private Integer revision;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String claims;

    @Column(columnDefinition = "TEXT")
    private String coveredEntryIds;

    @Column(columnDefinition = "TEXT")
    private String uncoveredEntryIds;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SummaryStatus status;

    @ColumnDefault("false")
    @Column(nullable = false)
    private boolean needsReview;

    @Column(columnDefinition = "TEXT")
    private String reviewReasons;

    @ColumnDefault("false")
    @Column(nullable = false)
    private boolean noContent;

    @Column(columnDefinition = "TEXT")
    private String rawResponse;

    @Column(length = 100)
    private String modelVersion;

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

    public static SummaryResult of(Long childId, LocalDate entryDate, Long institutionId, int revision,
            String content, String claims, String coveredEntryIds, String uncoveredEntryIds, String rawResponse) {
        requireGroup(childId, entryDate, institutionId, revision);
        requireContent(content);
        return new SummaryResult(childId, entryDate, institutionId, revision, content, claims, coveredEntryIds,
                uncoveredEntryIds, rawResponse);
    }

    public static SummaryResult noContent(Long childId, LocalDate entryDate, Long institutionId, int revision,
            String uncoveredEntryIds, String rawResponse) {
        requireGroup(childId, entryDate, institutionId, revision);
        SummaryResult summary = new SummaryResult(childId, entryDate, institutionId, revision, "", EMPTY_ARRAY,
                EMPTY_ARRAY, uncoveredEntryIds, rawResponse);
        summary.noContent = true;
        return summary;
    }

    public boolean isBeforeApproval() {
        return this.status == SummaryStatus.GENERATED || this.status == SummaryStatus.GATE1_PENDING;
    }

    public void overwrite(String content, String claims, String coveredEntryIds, String uncoveredEntryIds,
            String rawResponse) {
        if (!isBeforeApproval()) {
            throw new IllegalStateException("승인 전 요약만 덮어쓸 수 있습니다: " + this.status);
        }
        requireContent(content);
        replace(content, claims, coveredEntryIds, uncoveredEntryIds, rawResponse, false);
    }

    public void overwriteNoContent(String uncoveredEntryIds, String rawResponse) {
        if (!isBeforeApproval()) {
            throw new IllegalStateException("승인 전 요약만 덮어쓸 수 있습니다: " + this.status);
        }
        replace("", EMPTY_ARRAY, EMPTY_ARRAY, uncoveredEntryIds, rawResponse, true);
    }

    private void replace(String content, String claims, String coveredEntryIds, String uncoveredEntryIds,
            String rawResponse, boolean noContent) {
        this.content = content;
        this.claims = claims;
        this.coveredEntryIds = coveredEntryIds;
        this.uncoveredEntryIds = uncoveredEntryIds;
        this.rawResponse = rawResponse;
        this.noContent = noContent;
        this.needsReview = false;
        this.reviewReasons = null;
    }

    public void markReview(boolean needsReview, String reviewReasons) {
        this.needsReview = needsReview;
        this.reviewReasons = reviewReasons;
    }

    public void approve() {
        requireStatus(SummaryStatus.GATE1_PENDING);
        requireContentful();
        this.status = SummaryStatus.APPROVED;
    }

    public void approveWithClaims(String claims) {
        requireStatus(SummaryStatus.GATE1_PENDING);
        requireContentful();
        if (claims == null || claims.isBlank()) {
            throw new IllegalArgumentException("고쳐 승인한 요약의 claims 는 필수입니다.");
        }
        this.claims = claims;
        this.status = SummaryStatus.APPROVED;
    }

    public void hold() {
        requireStatus(SummaryStatus.GATE1_PENDING);
        requireContentful();
        this.status = SummaryStatus.HOLD;
    }

    public void confirmNoContent() {
        requireNoContentPending();
        this.status = SummaryStatus.CONFIRMED;
    }

    public void revertNoContent() {
        requireNoContentPending();
        this.status = SummaryStatus.REVERTED;
    }

    public void remove() {
        if (this.status != SummaryStatus.GATE1_PENDING && this.status != SummaryStatus.HOLD) {
            throw new IllegalStateException("검토 대기나 보류된 요약만 제거할 수 있습니다: " + this.status);
        }
        requireContentful();
        this.status = SummaryStatus.REMOVED;
    }

    public void reopen() {
        requireStatus(SummaryStatus.HOLD);
        this.status = SummaryStatus.GATE1_PENDING;
    }

    private void requireStatus(SummaryStatus expected) {
        if (this.status != expected) {
            throw new IllegalStateException("요약 상태가 " + expected + "가 아닙니다: " + this.status);
        }
    }

    private void requireContentful() {
        if (this.noContent) {
            throw new IllegalStateException("요약할 내용이 없는 요약은 확인하거나 되돌리기만 할 수 있습니다: " + this.id);
        }
    }

    private void requireNoContentPending() {
        requireStatus(SummaryStatus.GATE1_PENDING);
        if (!this.noContent) {
            throw new IllegalStateException("요약할 내용이 없는 요약만 확인하거나 되돌릴 수 있습니다: " + this.id);
        }
    }

    private static void requireGroup(Long childId, LocalDate entryDate, Long institutionId, int revision) {
        if (childId == null || entryDate == null || institutionId == null) {
            throw new IllegalArgumentException("요약 묶음의 아동·날짜·기관은 필수입니다.");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("요약 판 번호는 1부터입니다: " + revision);
        }
    }

    private static void requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("요약 본문은 필수입니다.");
        }
    }
}
