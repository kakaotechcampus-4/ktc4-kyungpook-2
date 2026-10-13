package com.itda.backend.domain;

import java.time.LocalDateTime;

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
 * 기관이 보호자에게 건네는 일회용 초대 코드. 코드는 본인 확인이 아니라 "이 아이와 연결해도 된다"는 권한이다.
 *
 * <p>수락해도, 취소해도 행을 지우지 않는다. 같은 코드가 다시 발급되지 않게 유니크 제약을 계속 차지하고,
 * 누가 언제 초대했고 누가 수락했는지 이력이 남아야 한다 (DB 스키마 §5.3).
 *
 * <p>만료는 상태로 표시하지 않고 {@code expiresAt} 으로만 판단한다. 판단 시각은 서비스가 주입받은
 * {@code Clock} 으로 구해 넘긴다.
 */
@Entity
@Table(
        name = "invitation",
        uniqueConstraints = @UniqueConstraint(name = Invitation.UK_INVITATION_CODE, columnNames = "code"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Invitation {

    public static final String UK_INVITATION_CODE = "uk_invitation_code";
    public static final int CODE_LENGTH = 8;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = CODE_LENGTH)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InvitationStatus status;

    @Column(name = "accepted_user_id")
    private Long acceptedUserId;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private Invitation(Long childId, Long organizationId, String code, LocalDateTime expiresAt) {
        this.childId = childId;
        this.organizationId = organizationId;
        this.code = code;
        this.expiresAt = expiresAt;
        this.status = InvitationStatus.PENDING;
    }

    public static Invitation issue(Long childId, Long organizationId, String code, LocalDateTime expiresAt) {
        if (childId == null || organizationId == null) {
            throw new IllegalArgumentException("아동 ID와 기관 ID는 필수입니다.");
        }
        if (code == null || code.length() != CODE_LENGTH) {
            throw new IllegalArgumentException("초대 코드는 8자리여야 합니다.");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("만료 시각은 필수입니다.");
        }
        return new Invitation(childId, organizationId, code, expiresAt);
    }

    /** 아직 수락·취소되지 않았고, {@code now} 가 만료 시각 전이다. 만료 시각과 같으면 이미 만료다. */
    public boolean isUsable(LocalDateTime now) {
        return this.status == InvitationStatus.PENDING && now.isBefore(this.expiresAt);
    }

    /**
     * 보호자가 코드를 썼다. 서비스가 잠금을 얻은 뒤 같은 시각으로 {@link #isUsable} 을 먼저 확인하지만,
     * 쓸 수 없는 코드로 연결이 생기면 권한 없는 사람이 아이 기록에 닿게 되므로 엔티티도 스스로 막는다.
     */
    public void accept(Long userId, LocalDateTime now) {
        if (userId == null) {
            throw new IllegalArgumentException("수락한 보호자 ID는 필수입니다.");
        }
        if (!isUsable(now)) {
            throw new IllegalStateException("쓸 수 없는 초대 코드입니다.");
        }
        this.status = InvitationStatus.ACCEPTED;
        this.acceptedUserId = userId;
    }

    /** 기관이 아직 쓸 수 있는 코드를 거둬들였다. 이미 수락·취소·만료된 코드는 취소할 것이 없다. */
    public void cancel(LocalDateTime now) {
        if (!isUsable(now)) {
            throw new IllegalStateException("쓸 수 없는 초대 코드입니다.");
        }
        this.status = InvitationStatus.CANCELLED;
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
