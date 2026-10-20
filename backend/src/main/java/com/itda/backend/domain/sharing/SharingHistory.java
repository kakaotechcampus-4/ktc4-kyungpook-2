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
 * <p>한 번 생기면 고치지 않는 권한 행이라 {@code updated_at} 이 없다 (§3.2). {@code readAt} 한 칸만
 * 나중에 채워진다 — 열람 방식에서 "언제 봤나" 를 남기지 않으면 아무 흔적이 없다 (CLAUDE.md Auditability).
 * 채우는 쪽은 읽음 처리 API(O-51, 미구현)다.
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

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
