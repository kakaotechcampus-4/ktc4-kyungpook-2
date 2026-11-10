package com.itda.backend.domain.invitation;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.itda.backend.global.jpa.TimestampedEntity;

@Entity
@Table(
        name = "child_guardian",
        uniqueConstraints = @UniqueConstraint(name = ChildGuardian.UK_CHILD_GUARDIAN, columnNames = {"child_id", "user_id"}),
        indexes = @Index(name = "idx_child_guardian_user_id", columnList = "user_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChildGuardian extends TimestampedEntity {

    public static final String UK_CHILD_GUARDIAN = "uk_child_guardian_child_id_user_id";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "invitation_id", nullable = false)
    private Long invitationId;

    @Column(name = "linked_at", nullable = false)
    private LocalDateTime linkedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    private ChildGuardian(Long childId, Long userId, Long invitationId) {
        this.childId = childId;
        this.userId = userId;
        this.invitationId = invitationId;
        this.linkedAt = LocalDateTime.now();
    }

    public static ChildGuardian link(Long childId, Long userId, Long invitationId) {
        if (childId == null || userId == null || invitationId == null) {
            throw new IllegalArgumentException("아동 ID, 보호자 ID, 초대 ID는 필수입니다.");
        }
        return new ChildGuardian(childId, userId, invitationId);
    }

    public void relink(Long invitationId) {
        if (invitationId == null) {
            throw new IllegalArgumentException("초대 ID는 필수입니다.");
        }
        if (!isDeleted()) {
            throw new IllegalStateException("이미 연결된 보호자입니다.");
        }
        this.invitationId = invitationId;
        this.linkedAt = LocalDateTime.now();
        this.deletedAt = null;
    }

    public void delete() {
        this.deletedAt = LocalDateTime.now();
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
    }
}
