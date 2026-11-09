package com.itda.backend.domain.approval;

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

@Entity
@Table(name = "human_review")
@Getter
@NoArgsConstructor
public class Approval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ApprovalTargetType targetType;

    @Column(nullable = false)
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApprovalReviewType reviewType;

    @Column(nullable = false)
    private Long reviewerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApprovalDecision decision;

    @Column(columnDefinition = "TEXT")
    private String comment;

    @Column(nullable = false)
    private LocalDateTime reviewedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public Approval(
            ApprovalTargetType targetType,
            Long targetId,
            ApprovalReviewType reviewType,
            Long reviewerId,
            ApprovalDecision decision,
            String comment) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.reviewType = reviewType;
        this.reviewerId = reviewerId;
        this.decision = decision;
        this.comment = comment;
        LocalDateTime now = LocalDateTime.now();
        this.reviewedAt = now;
        this.createdAt = now;
    }

    public static Approval gate1(Long summaryId, Long reviewerId, ApprovalDecision decision) {
        return new Approval(ApprovalTargetType.SUMMARY, summaryId, ApprovalReviewType.GATE1, reviewerId, decision,
                null);
    }

    public static Approval gate2(Long insightId, Long reviewerId, ApprovalDecision decision) {
        return new Approval(ApprovalTargetType.INSIGHT, insightId, ApprovalReviewType.GATE2, reviewerId, decision,
                null);
    }
}
