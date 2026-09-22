package com.itda.backend.domain;

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

// DB초안 노션의 human_review 테이블. Gate1/Gate2 모두 이 테이블 하나를 공유하는 폴리모픽 설계 —
// targetType(SUMMARY/INSIGHT) + targetId로 대상을 가리키고, FK는 두지 않는다(§16 원칙).
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
}
