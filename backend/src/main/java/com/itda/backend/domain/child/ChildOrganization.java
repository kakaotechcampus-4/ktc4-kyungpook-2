package com.itda.backend.domain.child;

import java.time.LocalDateTime;

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
import com.itda.backend.global.jpa.TimestampedEntity;

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
public class ChildOrganization extends TimestampedEntity {

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

    public static ChildOrganization of(Long childId, Long organizationId, String externalId) {
        if (childId == null || organizationId == null) {
            throw new IllegalArgumentException("아동 ID와 기관 ID는 필수입니다.");
        }
        if (externalId != null && (externalId.isBlank() || externalId.length() > EXTERNAL_ID_MAX_LENGTH)) {
            throw new IllegalArgumentException("관리번호는 공백일 수 없고 50자 이하여야 합니다.");
        }
        return new ChildOrganization(childId, organizationId, externalId);
    }

    public void delete() {
        this.deletedAt = LocalDateTime.now();
        this.externalId = null;
    }

    public void restore() {
        this.deletedAt = null;
    }

    public void restore(String externalId) {
        if (externalId == null || externalId.isBlank() || externalId.length() > EXTERNAL_ID_MAX_LENGTH) {
            throw new IllegalArgumentException("관리번호는 공백일 수 없고 50자 이하여야 합니다.");
        }
        this.deletedAt = null;
        this.externalId = externalId;
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
    }
}
