package com.itda.backend.domain.invitation;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
        name = "invitation",
        uniqueConstraints = @UniqueConstraint(name = Invitation.UK_INVITATION_CODE, columnNames = "code"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Invitation extends TimestampedEntity {

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

    public boolean isUsable(LocalDateTime now) {
        return this.status == InvitationStatus.PENDING && now.isBefore(this.expiresAt);
    }

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

    public void cancel(LocalDateTime now) {
        if (!isUsable(now)) {
            throw new IllegalStateException("쓸 수 없는 초대 코드입니다.");
        }
        this.status = InvitationStatus.CANCELLED;
    }
}
