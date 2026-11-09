package com.itda.backend.domain.organization;

import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.itda.backend.domain.invitation.Invitation;
import com.itda.backend.global.jpa.TimestampedEntity;

@Entity
@Table(
        name = "organization_handover",
        uniqueConstraints = @UniqueConstraint(name = OrganizationHandover.UK_CODE, columnNames = "code"),
        indexes = @Index(name = "idx_organization_handover_organization", columnList = "organization_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrganizationHandover extends TimestampedEntity {

    public static final String UK_CODE = "uk_organization_handover_code";
    public static final int CODE_LENGTH = Invitation.CODE_LENGTH;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = CODE_LENGTH)
    private String code;

    @Column(name = "issued_by", nullable = false)
    private Long issuedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrganizationHandoverStatus status;

    @Column(name = "accepted_user_id")
    private Long acceptedUserId;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    private OrganizationHandover(Long organizationId, Long issuedBy, String code, LocalDateTime expiresAt) {
        this.organizationId = organizationId;
        this.issuedBy = issuedBy;
        this.code = code;
        this.expiresAt = expiresAt;
        this.status = OrganizationHandoverStatus.PENDING;
    }

    public static OrganizationHandover issue(Long organizationId, Long issuedBy, String code, LocalDateTime expiresAt) {
        if (organizationId == null || issuedBy == null) {
            throw new IllegalArgumentException("기관 ID와 발급한 담당자 ID는 필수입니다.");
        }
        if (code == null || code.length() != CODE_LENGTH) {
            throw new IllegalArgumentException("인계 코드는 8자리여야 합니다.");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("만료 시각은 필수입니다.");
        }
        return new OrganizationHandover(organizationId, issuedBy, code, expiresAt);
    }

    public boolean isUsable(LocalDateTime now) {
        return this.status == OrganizationHandoverStatus.PENDING && now.isBefore(this.expiresAt);
    }

    public void accept(Long userId, LocalDateTime now) {
        if (userId == null) {
            throw new IllegalArgumentException("받은 사람 ID는 필수입니다.");
        }
        if (Objects.equals(userId, this.issuedBy)) {
            throw new IllegalStateException("발급한 담당자는 자기 인계 코드를 받을 수 없습니다.");
        }
        if (!isUsable(now)) {
            throw new IllegalStateException("쓸 수 없는 인계 코드입니다.");
        }
        this.status = OrganizationHandoverStatus.ACCEPTED;
        this.acceptedUserId = userId;
        this.acceptedAt = now;
    }

    public void cancel(LocalDateTime now) {
        if (!isUsable(now)) {
            throw new IllegalStateException("쓸 수 없는 인계 코드입니다.");
        }
        this.status = OrganizationHandoverStatus.CANCELLED;
    }
}
