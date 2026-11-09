package com.itda.backend.domain.checklist;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "checklist_target",
        uniqueConstraints = @UniqueConstraint(
                name = ChecklistTarget.UK_CHECKLIST_TARGET,
                columnNames = {"checklist_id", "child_id"}),
        indexes = @Index(name = "idx_checklist_target_child_id", columnList = "child_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChecklistTarget {

    public static final String UK_CHECKLIST_TARGET = "uk_checklist_target_checklist_child";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "checklist_id", nullable = false)
    private Long checklistId;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private ChecklistTarget(Long checklistId, Long childId, LocalDateTime createdAt) {
        this.checklistId = checklistId;
        this.childId = childId;
        this.createdAt = createdAt;
    }

    public static ChecklistTarget of(Long checklistId, Long childId, LocalDateTime at) {
        if (checklistId == null || childId == null || at == null) {
            throw new IllegalArgumentException("받는 아이의 요청 · 아이 · 시각은 필수입니다.");
        }
        return new ChecklistTarget(checklistId, childId, at);
    }

    public boolean isConfirmed() {
        return confirmedAt != null;
    }
}
