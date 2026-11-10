package com.itda.backend.domain.child;

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

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.itda.backend.global.jpa.TimestampedEntity;

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
public class ChildOrganizationRequest extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChildOrganizationRequestStatus status;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

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

    private void decide(ChildOrganizationRequestStatus status, Long userId, LocalDateTime now) {
        if (!isPending()) {
            throw new IllegalStateException("이미 처리된 연결 요청입니다.");
        }
        this.status = status;
        this.decidedBy = userId;
        this.decidedAt = now;
    }
}
