package com.itda.backend.repository.child;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.child.ChildOrganizationRequest;
import com.itda.backend.domain.child.ChildOrganizationRequestStatus;

public interface ChildOrganizationRequestRepository extends JpaRepository<ChildOrganizationRequest, Long> {

    /**
     * 보호자가 같은 기관에 다시 요청했는지. 아이 행을 잠근 뒤 부른다 — 아이 잠금이 같은 보호자의 동시 요청을 줄 세우므로
     * 대기 행이 둘 생기지 않는다.
     */
    Optional<ChildOrganizationRequest> findFirstByChildIdAndOrganizationIdAndRequestedByAndStatus(
            Long childId, Long organizationId, Long requestedBy, ChildOrganizationRequestStatus status);

    /**
     * 기관의 수락·거절용. 아이 행을 잠근 뒤 대기 행을 잠그며 처음 읽는다 — 미리 읽어 둔 엔티티가 있으면 그사이 연결 해제로
     * 취소된 행을 옛 {@code PENDING} 으로 보고 수락할 수 있다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ChildOrganizationRequest r"
            + " where r.childId = :childId and r.organizationId = :organizationId"
            + " and r.status = com.itda.backend.domain.child.ChildOrganizationRequestStatus.PENDING"
            + " order by r.id")
    List<ChildOrganizationRequest> findPendingByChildIdAndOrganizationIdForUpdate(
            @Param("childId") Long childId, @Param("organizationId") Long organizationId);

    /** 보호자 연결 해제용. 그 보호자가 이 아이에 대해 낸 대기 요청만 거둔다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ChildOrganizationRequest r"
            + " where r.childId = :childId and r.requestedBy = :requestedBy"
            + " and r.status = com.itda.backend.domain.child.ChildOrganizationRequestStatus.PENDING")
    List<ChildOrganizationRequest> findPendingByChildIdAndRequestedByForUpdate(
            @Param("childId") Long childId, @Param("requestedBy") Long requestedBy);

    /** 기관 화면의 연결 요청 목록. 아이별로 묶는 것은 서비스가 한다. */
    List<ChildOrganizationRequest> findByOrganizationIdAndStatusOrderByCreatedAtAscIdAsc(
            Long organizationId, ChildOrganizationRequestStatus status);

    /** 보호자 화면의 "수락 대기 중". 누구의 요청이든 아이의 보호자 모두에게 보인다. */
    List<ChildOrganizationRequest> findByChildIdInAndStatusOrderByCreatedAtAscIdAsc(
            Collection<Long> childIds, ChildOrganizationRequestStatus status);
}
