package com.itda.backend.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 아동과 기관의 연결(N:M). 기관별 매칭 명부와 "이 아이가 우리 기관 소속인가" 판단이 여기서 나온다.
 *
 * <p>연결 해제는 행을 지우지 않고 {@code deletedAt} 을 찍는다. 삭제된 행도 유니크 제약을 차지하므로
 * 다시 연결할 때는 새 행을 만들지 말고 기존 행을 {@link #restore()} 한다 (DB 스키마 §3.3).
 */
@Entity
@Table(
        name = "child_organization",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_child_organization_child_id_organization_id",
                columnNames = {"child_id", "organization_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChildOrganization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    private ChildOrganization(Long childId, Long organizationId) {
        this.childId = childId;
        this.organizationId = organizationId;
    }

    public static ChildOrganization of(Long childId, Long organizationId) {
        if (childId == null || organizationId == null) {
            throw new IllegalArgumentException("아동 ID와 기관 ID는 필수입니다.");
        }
        return new ChildOrganization(childId, organizationId);
    }

    public void delete() {
        this.deletedAt = LocalDateTime.now();
    }

    public void restore() {
        this.deletedAt = null;
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
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
