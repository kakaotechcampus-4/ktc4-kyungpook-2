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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 기관 담당자가 후임에게 건네는 일회용 인계 코드 (#478).
 *
 * <p>후임이 카카오로 가입하면서 이 코드를 넣으면 같은 기관의 담당자가 되고, 발급한 담당자 계정은 탈퇴로 정리된다.
 * 사업자등록번호로 이어받게 하지 않는 이유는 사업자번호가 공개 정보라서다 — 이미 권한 있는 사람이 넘겨줘야만 이어진다.
 *
 * <p>보호자 초대 코드({@code Invitation})와 같은 규칙을 따른다. 수락해도 취소해도 행을 지우지 않고(같은 코드가 다시
 * 발급되지 않게 유니크 제약을 계속 차지하고 이력을 남긴다), 만료는 {@code expiresAt} 으로만 판단한다.
 */
@Entity
@Table(
        name = "organization_handover",
        uniqueConstraints = @UniqueConstraint(name = OrganizationHandover.UK_CODE, columnNames = "code"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrganizationHandover {

    public static final String UK_CODE = "uk_organization_handover_code";
    public static final int CODE_LENGTH = 8;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = CODE_LENGTH)
    private String code;

    /** 코드를 발급한 담당자. 후임이 받으면 이 계정을 탈퇴시킨다. */
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

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

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

    /** 아직 받지도 취소하지도 않았고, {@code now} 가 만료 시각 전이다. 만료 시각과 같으면 이미 만료다. */
    public boolean isUsable(LocalDateTime now) {
        return this.status == OrganizationHandoverStatus.PENDING && now.isBefore(this.expiresAt);
    }

    /**
     * 후임이 코드를 썼다. 서비스가 잠근 뒤 같은 시각으로 확인하지만, 쓸 수 없는 코드로 기관이 넘어가면 권한 없는 사람이
     * 아이 기록에 닿으므로 엔티티도 스스로 막는다.
     */
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

    /** 담당자가 아직 쓸 수 있는 코드를 거둬들였다(직접 취소하거나 새로 발급). */
    public void cancel(LocalDateTime now) {
        if (!isUsable(now)) {
            throw new IllegalStateException("쓸 수 없는 인계 코드입니다.");
        }
        this.status = OrganizationHandoverStatus.CANCELLED;
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
