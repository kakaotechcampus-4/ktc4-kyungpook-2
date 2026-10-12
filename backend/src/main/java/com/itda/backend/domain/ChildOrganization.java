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
 *
 * <p>{@code externalId} 는 기관이 쓰는 아동 관리번호다. 기관마다 번호가 달라서 아동이 아니라 연결이 가진다.
 * 같은 기관 안에서는 겹칠 수 없다.
 */
@Entity
@Table(
        name = "child_organization",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_child_organization_child_id_organization_id",
                        columnNames = {"child_id", "organization_id"}),
                @UniqueConstraint(
                        name = ChildOrganization.UK_ORGANIZATION_EXTERNAL_ID,
                        columnNames = {"organization_id", "external_id"})})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChildOrganization {

    public static final String UK_ORGANIZATION_EXTERNAL_ID = "uk_child_organization_organization_id_external_id";
    public static final int EXTERNAL_ID_MAX_LENGTH = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "external_id", length = EXTERNAL_ID_MAX_LENGTH)
    private String externalId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    private ChildOrganization(Long childId, Long organizationId, String externalId) {
        this.childId = childId;
        this.organizationId = organizationId;
        this.externalId = externalId;
    }

    public static ChildOrganization of(Long childId, Long organizationId) {
        return of(childId, organizationId, null);
    }

    /** 관리번호는 없어도 되지만, 있으면 공백만이어서는 안 되고 50자를 넘을 수 없다. */
    public static ChildOrganization of(Long childId, Long organizationId, String externalId) {
        if (childId == null || organizationId == null) {
            throw new IllegalArgumentException("아동 ID와 기관 ID는 필수입니다.");
        }
        if (externalId != null && (externalId.isBlank() || externalId.length() > EXTERNAL_ID_MAX_LENGTH)) {
            throw new IllegalArgumentException("관리번호는 공백일 수 없고 50자 이하여야 합니다.");
        }
        return new ChildOrganization(childId, organizationId, externalId);
    }

    /**
     * 관리번호도 함께 비운다. 해제된 행은 테이블에 남아 (organization_id, external_id) 유니크 제약을 계속
     * 차지하므로, 비우지 않으면 그 번호를 다른 아동에게 영영 줄 수 없다. 그래서 {@link #restore()} 해도
     * 예전 번호는 돌아오지 않는다 (DB 스키마 §3.3).
     */
    public void delete() {
        this.deletedAt = LocalDateTime.now();
        this.externalId = null;
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
