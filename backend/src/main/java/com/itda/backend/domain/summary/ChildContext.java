package com.itda.backend.domain.summary;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.itda.backend.global.jpa.CreatedAtEntity;

@Entity
@Table(
        name = "child_context",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_child_context_summary_result", columnNames = "summary_result_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChildContext extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    @Column(name = "summary_result_id", nullable = false)
    private Long summaryResultId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    private ChildContext(Long childId, Long summaryResultId, String content) {
        this.childId = childId;
        this.summaryResultId = summaryResultId;
        this.content = content;
    }

    public static ChildContext of(Long childId, Long summaryResultId, String content) {
        if (childId == null) {
            throw new IllegalArgumentException("대상 아동 ID는 필수입니다.");
        }
        if (summaryResultId == null) {
            throw new IllegalArgumentException("출처가 된 요약 ID는 필수입니다.");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("승인된 요약 본문은 필수입니다.");
        }
        return new ChildContext(childId, summaryResultId, content);
    }
}
