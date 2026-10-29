package com.itda.backend.domain.child;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
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
 * 보호자가 기관 코드로 "이 아이를 이 기관 명부에 올려 달라" 고 낸 요청. 기관이 관리번호를 넣어 수락하면
 * {@link ChildOrganization} 이 생기거나 되살아난다. 수락은 동의가 아니다 — 동의는 그 뒤 보호자가 따로 한다.
 *
 * <p>보호자마다 자기 행을 가진다. 엄마와 아빠가 같은 기관에 요청하면 대기 행이 둘이다. 엄마가 아이 연결을 끊으면
 * 엄마의 행만 거두고 아빠의 요청은 남는다. 기관은 (아이, 기관) 단위로 묶어 보고 한 번에 수락·거절한다.
 *
 * <p>유니크 제약을 걸지 않는다. 거절·취소된 뒤 다시 요청하면 새 행을 만드는 이력이라 같은 (아이, 기관, 보호자)가
 * 여러 번 나온다. "대기 행은 보호자마다 하나" 는 아이 행 잠금이 지킨다 (DB 스키마 §3.3).
 *
 * <p>상태가 삭제를 대신하므로 {@code deleted_at} 을 두지 않는다.
 */
@Entity
@Table(
        name = "child_organization_request",
        indexes = {
                @Index(name = "idx_child_organization_request_organization_id_status",
                        columnList = "organization_id, status"),
                @Index(name = "idx_child_organization_request_child_id_organization_id",
                        columnList = "child_id, organization_id")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChildOrganizationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    /** 요청한 보호자 {@code users.id}. 이 행의 주인이다 */
    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChildOrganizationRequestStatus status;

    /** 수락·거절한 기관 담당자 {@code users.id}. 취소는 사람이 처리한 게 아니라 비어 있다 */
    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private ChildOrganizationRequest(Long childId, Long organizationId, Long requestedBy) {
        this.childId = childId;
        this.organizationId = organizationId;
        this.requestedBy = requestedBy;
        this.status = ChildOrganizationRequestStatus.PENDING;
    }

    public static ChildOrganizationRequest request(Long childId, Long organizationId, Long guardianId) {
        if (childId == null || organizationId == null || guardianId == null) {
            throw new IllegalArgumentException("아동 ID, 기관 ID, 보호자 ID는 필수입니다.");
        }
        return new ChildOrganizationRequest(childId, organizationId, guardianId);
    }

    public void accept(Long userId, LocalDateTime now) {
        decide(ChildOrganizationRequestStatus.ACCEPTED, userId, now);
    }

    public void reject(Long userId, LocalDateTime now) {
        decide(ChildOrganizationRequestStatus.REJECTED, userId, now);
    }

    public void cancel(LocalDateTime now) {
        decide(ChildOrganizationRequestStatus.CANCELLED, null, now);
    }

    public boolean isPending() {
        return this.status == ChildOrganizationRequestStatus.PENDING;
    }

    /** 처리된 요청은 다시 처리하지 않는다. 다시 요청하려면 새 행을 만든다. */
    private void decide(ChildOrganizationRequestStatus status, Long userId, LocalDateTime now) {
        if (!isPending()) {
            throw new IllegalStateException("이미 처리된 연결 요청입니다.");
        }
        this.status = status;
        this.decidedBy = userId;
        this.decidedAt = now;
    }

    /**
     * 같은 보호자가 다시 요청하면 DB 에서 읽은 {@code createdAt} 을 돌려준다. 리눅스 시계는 나노초까지 주는데
     * DB 는 마이크로초까지만 저장하므로, 처음 응답과 재요청 응답의 {@code requestedAt} 이 같도록 미리 자른다.
     */
    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }
}
