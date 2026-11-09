package com.itda.backend.domain.insight;

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

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.itda.backend.global.jpa.TimestampedEntity;

@Entity
@Table(name = "insight_result",
        indexes = @Index(name = "idx_insight_result_child_id", columnList = "child_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InsightResult extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    @Column(name = "period_from", nullable = false)
    private LocalDate periodFrom;

    @Column(name = "period_to", nullable = false)
    private LocalDate periodTo;

    @Column(nullable = false)
    private Integer revision;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String situation;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String behavior;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String supports;

    @Column(name = "claim_ids", columnDefinition = "TEXT")
    private String claimIds;

    @Column(name = "source_entry_ids", columnDefinition = "TEXT")
    private String sourceEntryIds;

    @Column(name = "source_institution_ids", columnDefinition = "TEXT")
    private String sourceInstitutionIds;

    @Column(name = "looked_up_claim_ids", columnDefinition = "TEXT")
    private String lookedUpClaimIds;

    @Column(name = "cross_institution", nullable = false)
    private boolean crossInstitution;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private InsightStatus status;

    @Column(name = "raw_response", columnDefinition = "TEXT")
    private String rawResponse;

    @Column(name = "model_version", length = 100)
    private String modelVersion;

    private InsightResult(Long childId, LocalDate periodFrom, LocalDate periodTo, int revision,
            String situation, String behavior, String content, String supports, String claimIds,
            String sourceEntryIds, String sourceInstitutionIds, boolean crossInstitution, String rawResponse) {
        this.childId = childId;
        this.periodFrom = periodFrom;
        this.periodTo = periodTo;
        this.revision = revision;
        this.situation = situation;
        this.behavior = behavior;
        this.content = content;
        this.supports = supports;
        this.claimIds = claimIds;
        this.sourceEntryIds = sourceEntryIds;
        this.sourceInstitutionIds = sourceInstitutionIds;
        this.crossInstitution = crossInstitution;
        this.rawResponse = rawResponse;
        this.status = InsightStatus.GATE2_PENDING;
    }

    public static InsightResult of(Long childId, LocalDate periodFrom, LocalDate periodTo, int revision,
            String situation, String behavior, String content, String supports, String claimIds,
            String sourceEntryIds, String sourceInstitutionIds, boolean crossInstitution, String rawResponse) {
        return of(childId, periodFrom, periodTo, revision, situation, behavior, content, supports, claimIds,
                sourceEntryIds, sourceInstitutionIds, crossInstitution, rawResponse, null);
    }

    public static InsightResult of(Long childId, LocalDate periodFrom, LocalDate periodTo, int revision,
            String situation, String behavior, String content, String supports, String claimIds,
            String sourceEntryIds, String sourceInstitutionIds, boolean crossInstitution, String rawResponse,
            String lookedUpClaimIds) {
        if (childId == null || periodFrom == null || periodTo == null) {
            throw new IllegalArgumentException("인사이트의 아동·기간은 필수입니다.");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("인사이트 판 번호는 1부터입니다: " + revision);
        }
        requireText(situation, "상황");
        requireText(behavior, "반응");
        requireText(content, "본문");
        InsightResult result = new InsightResult(childId, periodFrom, periodTo, revision, situation, behavior, content,
                supports, claimIds, sourceEntryIds, sourceInstitutionIds, crossInstitution, rawResponse);
        result.lookedUpClaimIds = lookedUpClaimIds;
        return result;
    }

    public void approveGate2() {
        requireGate2Pending();
        this.status = InsightStatus.APPROVED;
    }

    public void holdGate2() {
        requireGate2Pending();
        this.status = InsightStatus.HOLD;
    }

    private void requireGate2Pending() {
        if (this.status != InsightStatus.GATE2_PENDING) {
            throw new IllegalStateException("이미 처리된 인사이트입니다: id=" + id + " status=" + status);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("인사이트 " + name + "은 필수입니다.");
        }
    }
}
