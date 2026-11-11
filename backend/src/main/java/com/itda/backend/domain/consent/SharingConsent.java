package com.itda.backend.domain.consent;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.itda.backend.global.jpa.TimestampedEntity;

@Entity
@Table(
        name = "sharing_consent",
        indexes = @Index(name = "idx_sharing_consent_child_id_organization_id", columnList = "child_id, organization_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SharingConsent extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    @Column(name = "guardian_id", nullable = false)
    private Long guardianId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Convert(converter = ConsentFieldsConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private List<ConsentField> scope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SharingConsentStatus status;

    @Column(name = "revoked_by")
    private Long revokedBy;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoke_reason", length = 30)
    private ConsentRevokeReason revokeReason;

    private SharingConsent(Long childId, Long guardianId, Long organizationId, List<ConsentField> scope) {
        this.childId = childId;
        this.guardianId = guardianId;
        this.organizationId = organizationId;
        this.scope = scope;
        this.status = SharingConsentStatus.ACTIVE;
    }

    public static SharingConsent grant(Long childId, Long guardianId, Long organizationId, Collection<ConsentField> fields) {
        if (childId == null || guardianId == null || organizationId == null) {
            throw new IllegalArgumentException("아동 ID, 보호자 ID, 기관 ID는 필수입니다.");
        }
        return new SharingConsent(childId, guardianId, organizationId, normalize(fields));
    }

    public static List<ConsentField> normalize(Collection<ConsentField> fields) {
        if (!isValidScope(fields)) {
            throw new IllegalArgumentException("공유 항목은 한 개 이상이어야 하고 비어 있는 항목이 있으면 안 됩니다.");
        }
        return List.copyOf(EnumSet.copyOf(fields));
    }

    public static boolean isValidScope(Collection<ConsentField> fields) {
        return fields != null && !fields.isEmpty() && fields.stream().allMatch(Objects::nonNull);
    }

    public void changeScope(Collection<ConsentField> fields) {
        requireActive();
        this.scope = normalize(fields);
    }

    public void revoke(Long actorId, ConsentRevokeReason reason, LocalDateTime revokedAt) {
        if (reason == null || revokedAt == null) {
            throw new IllegalArgumentException("철회 사유와 시각은 필수입니다.");
        }
        requireActive();
        this.status = SharingConsentStatus.REVOKED;
        this.revokedBy = actorId;
        this.revokedAt = revokedAt;
        this.revokeReason = reason;
    }

    public boolean isActive() {
        return this.status == SharingConsentStatus.ACTIVE;
    }

    public boolean allows(ConsentField field) {
        return isActive() && this.scope.contains(field);
    }

    private void requireActive() {
        if (!isActive()) {
            throw new IllegalStateException("이미 철회된 동의입니다.");
        }
    }
}
