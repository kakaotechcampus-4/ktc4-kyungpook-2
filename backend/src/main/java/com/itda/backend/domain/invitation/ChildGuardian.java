package com.itda.backend.domain.invitation;

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
 * 아동과 보호자의 연결(N:M). 기관 구분 없는 전역 연결이다 — 끊으면 모든 기관에서 이 보호자는 아이에 닿지 못한다.
 *
 * <p>연결 해제는 행을 지우지 않고 {@code deletedAt} 을 찍는다. 삭제된 행도 유니크 제약을 차지하므로
 * 다시 연결할 때는 새 행을 만들지 말고 {@link #relink} 로 되살린다 (DB 스키마 §3.3).
 *
 * <p>{@code invitationId} 는 지금 연결을 만든 초대다. 기관은 자기가 발급한 코드로 생긴 연결만 끊을 수 있어서,
 * 그 판단 근거로 남긴다. 다시 연결되면 새 초대로 바뀐다.
 *
 * <p>보호자 권한 확인은 반드시 {@code deletedAt IS NULL} 을 본다.
 */
@Entity
@Table(
        name = "child_guardian",
        uniqueConstraints = @UniqueConstraint(name = ChildGuardian.UK_CHILD_GUARDIAN, columnNames = {"child_id", "user_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChildGuardian {

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

    /** 지금 연결이 생긴 시각. 다시 연결되면 그 시각으로 바뀐다 — {@code createdAt} 은 처음 연결된 시각으로 남는다. */
    @Column(name = "linked_at", nullable = false)
    private LocalDateTime linkedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

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

    /** 해제된 연결을 새 초대로 되살린다. 살아 있는 연결을 덮어쓰면 해제 권한의 출처가 바뀌므로 막는다. */
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
