package com.itda.backend.domain.sharing;

import java.time.LocalDateTime;

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

import com.itda.backend.domain.consent.ConsentRevokeReason;
import com.itda.backend.global.jpa.CreatedAtEntity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "sharing_history",
        uniqueConstraints = @UniqueConstraint(
                name = SharingHistory.UK_SHARING_HISTORY,
                columnNames = {"insight_result_id", "receiver_type", "receiver_id"}),
        indexes = @Index(name = "idx_sharing_history_receiver_type_receiver_id", columnList = "receiver_type, receiver_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SharingHistory extends CreatedAtEntity {

    public static final String UK_SHARING_HISTORY = "uk_sharing_history_insight_receiver";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    @Column(name = "insight_result_id", nullable = false)
    private Long insightResultId;

    @Column(name = "sender_organization_id", nullable = false)
    private Long senderOrganizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "receiver_type", nullable = false, length = 20)
    private ReceiverType receiverType;

    @Column(name = "receiver_id", nullable = false)
    private Long receiverId;

    @Column(name = "granted_at", nullable = false)
    private LocalDateTime grantedAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoke_reason", length = 30)
    private ConsentRevokeReason revokeReason;

    private SharingHistory(Long childId, Long insightResultId, Long senderOrganizationId,
            ReceiverType receiverType, Long receiverId) {
        this.childId = childId;
        this.insightResultId = insightResultId;
        this.senderOrganizationId = senderOrganizationId;
        this.receiverType = receiverType;
        this.receiverId = receiverId;
        this.grantedAt = LocalDateTime.now();
    }

    public static SharingHistory grant(Long childId, Long insightResultId, Long senderOrganizationId,
            ReceiverType receiverType, Long receiverId) {
        if (childId == null || insightResultId == null || senderOrganizationId == null
                || receiverType == null || receiverId == null) {
            throw new IllegalArgumentException("열람 권한의 아동·인사이트·발신 기관·수신자는 필수입니다.");
        }
        return new SharingHistory(childId, insightResultId, senderOrganizationId, receiverType, receiverId);
    }

    public void revoke(ConsentRevokeReason reason, LocalDateTime at) {
        if (reason == null || at == null) {
            throw new IllegalArgumentException("권한 철회 사유와 시각은 필수입니다.");
        }
        if (this.revokedAt != null) {
            return;
        }
        this.revokedAt = at;
        this.revokeReason = reason;
    }

    public void regrant(LocalDateTime at) {
        if (at == null) {
            throw new IllegalArgumentException("권한을 다시 여는 시각은 필수입니다.");
        }
        if (this.revokedAt == null) {
            return;
        }
        this.revokedAt = null;
        this.revokeReason = null;
        this.grantedAt = at;
    }

    public boolean isActive() {
        return this.revokedAt == null;
    }
}
