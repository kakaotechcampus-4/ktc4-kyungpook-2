package com.itda.backend.domain.sharing;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import com.itda.backend.domain.consent.ConsentRevokeReason;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * "이 인사이트를 이 수신자가 볼 수 있다" 는 권한 한 줄 (DB 스키마 §9.4).
 *
 * <p><b>보내는 행위가 없다.</b> 10/17 회의에서 발송이 아니라 <b>열람</b> 방식으로 정해져서, Gate 2 를
 * 승인하면 수신자에게 <b>보이게 된다</b>. 그래서 {@code status}({@code SHARED}·{@code BLOCKED}·
 * {@code FAILED})를 두지 않는다 — 열람 방식에는 "보내려다 막혔다" 는 순간이 없고 권한이 없으면 행이
 * 없을 뿐이다. 막힌 조회 시도는 {@code audit_logs}(O-53)가 받는다.
 *
 * <p><b>사건 이력이 아니라 상태 행이다.</b> 발송 방식이었을 때는 "언제 보냈나" 를 쌓는 이력이었지만,
 * 열람 방식에서는 한 수신자당 한 줄이 켜졌다 꺼졌다 한다. 그래서 {@code child_organization}·
 * {@code child_guardian} 과 같은 규칙을 따른다 — <b>지웠다 다시 만들지 않고 같은 행을 되살린다</b> (§3.3).
 * UNIQUE 가 {@code revoked_at} 을 포함하지 않으므로 새로 넣으면 제약에 걸린다.
 *
 * <p>{@code readAt} 은 나중에 채워진다 — 열람 방식에서 "언제 봤나" 를 남기지 않으면 아무 흔적이 없다
 * (CLAUDE.md Auditability). 채우는 쪽은 보호자 인사이트 단건과 기관 수신함 단건이다. 엔티티 메서드가 아니라
 * 조건부 UPDATE({@code SharingHistoryRepository.markReadIfUnread})로 찍는다 — 동시에 연 두 요청이 각자
 * 저장하면 나중 것이 처음 시각을 덮는다.
 */
@Entity
@Table(
        name = "sharing_history",
        uniqueConstraints = @UniqueConstraint(
                name = SharingHistory.UK_SHARING_HISTORY,
                columnNames = {"insight_result_id", "receiver_type", "receiver_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SharingHistory {

    /** 한 수신자당 한 행. Gate 2 승인 API 를 두 번 불러도 권한이 두 번 생기지 않는다. */
    public static final String UK_SHARING_HISTORY = "uk_sharing_history_insight_receiver";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    @Column(name = "insight_result_id", nullable = false)
    private Long insightResultId;

    /** Gate 2 를 승인한 기관. 수신함(O-50)이 "어디서 왔나" 로 보여준다. */
    @Column(name = "sender_organization_id", nullable = false)
    private Long senderOrganizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "receiver_type", nullable = false, length = 20)
    private ReceiverType receiverType;

    @Column(name = "receiver_id", nullable = false)
    private Long receiverId;

    /** 볼 수 있게 된 시각. */
    @Column(name = "granted_at", nullable = false)
    private LocalDateTime grantedAt;

    /** 처음 열어 본 시각. NULL 이면 아직 안 봤다. */
    @Column(name = "read_at")
    private LocalDateTime readAt;

    /** 권한을 거둔 시각. NULL 이면 살아 있다. 읽는 쪽은 반드시 이 칸을 본다 (§9.4). */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /**
     * 왜 거뒀나. {@code sharing_consent} 의 철회 사유를 그대로 쓴다 — 권한이 꺼지는 이유가 결국 동의가
     * 꺼지는 이유라, 새 enum 을 만들면 같은 뜻의 값이 두 벌이 된다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "revoke_reason", length = 30)
    private ConsentRevokeReason revokeReason;

    @Column(nullable = false)
    private LocalDateTime createdAt;

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
            // 하나라도 비면 "누가 무엇을 볼 수 있는지" 가 정해지지 않는다. 기관 경계를 넘는 권한이라 막는다.
            throw new IllegalArgumentException("열람 권한의 아동·인사이트·발신 기관·수신자는 필수입니다.");
        }
        return new SharingHistory(childId, insightResultId, senderOrganizationId, receiverType, receiverId);
    }

    /**
     * 권한을 거둔다. 행은 지우지 않고 시각과 사유만 남긴다 — 공유 거부도 감사 대상이다 (CLAUDE.md).
     *
     * <p>이미 거둔 권한을 다시 거두지 않는다. 두 경로(기관 동의 철회 · 보호자 연결 해제)가 같은 행을
     * 가리킬 수 있는데, 덮어쓰면 <b>처음 거둔 시각과 사유가 사라진다.</b>
     */
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

    /**
     * 거뒀던 권한을 다시 켠다. 동의가 다시 켜지거나 보호자가 다시 연결되면 <b>같은 행을 되살린다</b> —
     * 새로 넣으면 {@code (insight_result_id, receiver_type, receiver_id)} UNIQUE 에 걸린다 (§3.3).
     *
     * <p>⚠️ <b>되살리면 앞서 거둔 시각과 사유가 사라진다.</b> 한 수신자당 한 줄이라 켜고 끈 이력을 여기
     * 쌓을 자리가 없다 — 그 이력이 필요하면 {@code audit_logs}(O-53)가 받아야 한다.
     *
     * <p>살아 있는 권한에 다시 부르면 아무것도 하지 않는다. 같은 대상에게 두 번 열어 줘도 "언제부터
     * 볼 수 있었나" 가 뒤로 밀리지 않게 한다.
     */
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

    /** 지금 볼 수 있는 권한인지. */
    public boolean isActive() {
        return this.revokedAt == null;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
