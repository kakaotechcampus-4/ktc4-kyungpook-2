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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 보호자 한 명이 아이 한 명의 정보를 기관 한 곳에 공유해도 된다고 한 동의 (DB 스키마 §5.5).
 *
 * <p>이력 테이블이다. 철회해도 지우지 않고 {@code REVOKED} 로 남기며, 다시 동의하면 새 행을 만든다.
 * 그래서 (아이, 보호자, 기관) 에 UNIQUE 를 걸 수 없다. 같은 아이의 동의 변경은 서비스가 {@code child} 행을
 * 먼저 잠가 한 줄로 세운다 — 이 잠금 없이 동시에 동의하면 ACTIVE 행이 둘 생긴다.
 *
 * <p>{@code scope} 는 기관 단위로 하나다. 같은 기관의 ACTIVE 행은 언제나 같은 항목을 가진다 — 서비스가
 * 동의할 때마다 그 기관의 ACTIVE 행을 모두 맞춘다.
 */
@Entity
@Table(
        name = "sharing_consent",
        indexes = @Index(name = "idx_sharing_consent_child_id_organization_id", columnList = "child_id, organization_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SharingConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    /** 동의한 보호자의 {@code users.id}. */
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

    /** 철회한 사용자. 보호자 본인이 아닐 수 있다 — 다른 보호자, 연결을 끊은 기관 담당자. 스크립트가 끄면 비어 있다. */
    @Column(name = "revoked_by")
    private Long revokedBy;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoke_reason", length = 30)
    private ConsentRevokeReason revokeReason;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

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

    /**
     * 비어 있거나 {@code null} 이 섞인 항목은 받지 않는다. 같은 항목이 두 번 오면 하나로 합치고 enum 순서로 둔다 —
     * 같은 기관의 ACTIVE 행들이 순서만 달라 다른 값으로 보이지 않게 하려는 것이다.
     *
     * <p>API 경로에서는 요청 검증과 서비스가 먼저 거른다. 여기서 던지는 {@link IllegalArgumentException} 은
     * 마지막 방어선이라 응답이 500 이 된다.
     */
    public static List<ConsentField> normalize(Collection<ConsentField> fields) {
        if (!isValidScope(fields)) {
            throw new IllegalArgumentException("공유 항목은 한 개 이상이어야 하고 비어 있는 항목이 있으면 안 됩니다.");
        }
        return List.copyOf(EnumSet.copyOf(fields));
    }

    public static boolean isValidScope(Collection<ConsentField> fields) {
        return fields != null && !fields.isEmpty() && fields.stream().allMatch(Objects::nonNull);
    }

    /** 살아 있는 동의의 공유 항목을 바꾼다. 철회된 동의를 고치면 이력이 바뀌므로 막는다. */
    public void changeScope(Collection<ConsentField> fields) {
        requireActive();
        this.scope = normalize(fields);
    }

    /**
     * @param actorId 철회한 사용자. 사람이 아닌 경로(스크립트)면 {@code null}
     */
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

    /** 지금 이 항목을 공유하도록 허락돼 있는지. 꺼진 동의는 범위가 남아 있어도 허락이 아니다. */
    public boolean allows(ConsentField field) {
        return isActive() && this.scope.contains(field);
    }

    private void requireActive() {
        if (!isActive()) {
            throw new IllegalStateException("이미 철회된 동의입니다.");
        }
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
