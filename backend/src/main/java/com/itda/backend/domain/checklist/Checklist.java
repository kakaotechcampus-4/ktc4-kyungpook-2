package com.itda.backend.domain.checklist;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "checklist",
        indexes = @Index(name = "idx_checklist_organization_id_created_at", columnList = "organization_id, created_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Checklist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Convert(converter = ChecklistItemsConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private List<String> items;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    private Checklist(Long organizationId, List<String> items, Long createdBy, LocalDateTime createdAt) {
        this.organizationId = organizationId;
        this.items = List.copyOf(items);
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public static Checklist send(Long organizationId, List<String> items, Long createdBy, LocalDateTime at) {
        if (organizationId == null || createdBy == null || at == null || items == null || items.isEmpty()) {
            throw new IllegalArgumentException("준비물·확인사항의 기관 · 보낸 사람 · 시각 · 항목은 필수입니다.");
        }
        return new Checklist(organizationId, items, createdBy, at);
    }

    public boolean isCanceled() {
        return canceledAt != null;
    }
}
