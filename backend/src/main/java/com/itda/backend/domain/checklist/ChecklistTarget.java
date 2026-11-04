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

/**
 * 준비물·확인사항을 받은 아이 한 명 (DB 스키마 §5.9). 보낸 순간 명부에 있던 아이마다 한 줄이 생긴다.
 *
 * <p><b>확인은 아이 몫으로 한 번이다.</b> 그 아이의 보호자 중 한 명이 확인하면 끝이고, 되돌리지 않는다. 확인은 엔티티
 * 메서드가 아니라 조건부 UPDATE({@code ChecklistTargetRepository.confirmIfUnconfirmed})로 찍는다 — 엄마 · 아빠가
 * 동시에 확인해도 먼저 한 사람만 남는다.
 */
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

    /** 한 요청이 같은 아이에게 두 번 가지 않는다. */
    public static final String UK_CHECKLIST_TARGET = "uk_checklist_target_checklist_child";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "checklist_id", nullable = false)
    private Long checklistId;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    /** 확인한 시각. NULL 이면 아직 아무도 확인하지 않았다. */
    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    /** 확인한 보호자 ({@code users.id}). 연결이 끊겨도 지우지 않는다. */
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
