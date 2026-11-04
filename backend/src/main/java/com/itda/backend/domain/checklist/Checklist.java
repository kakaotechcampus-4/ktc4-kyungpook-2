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

/**
 * 기관이 보호자에게 보낸 준비물·확인사항 한 건 (DB 스키마 §5.8). 받은 아이마다의 확인은 {@link ChecklistTarget} 이 갖는다.
 *
 * <p><b>보낸 뒤 고치지 않는다.</b> 보호자가 확인한 내용이 나중에 바뀌면 확인의 뜻이 흐려진다. 잘못 보냈으면 내리고(
 * {@code canceled_at}) 다시 보낸다. 내리기는 엔티티 메서드가 아니라 조건부 UPDATE({@code ChecklistRepository.cancelIfActive})
 * 로 한다 — 두 요청이 동시에 "아직 안 내림"을 읽고 각자 저장하면 나중 것이 처음 시각을 덮는다.
 *
 * <p>기관 · 보낸 사람과 연관관계를 맺지 않는다. 다른 테이블처럼 FK 제약 없이 id 컬럼만 둔다.
 */
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

    /** 준비물 · 확인사항 줄. 1~10줄, 줄마다 1~100자는 요청 검증이 지킨다. */
    @Convert(converter = ChecklistItemsConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private List<String> items;

    /** 보낸 기관 담당자 ({@code users.id}). */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    /** 보낸 시각. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 내린 시각. NULL 이면 살아 있다. */
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
