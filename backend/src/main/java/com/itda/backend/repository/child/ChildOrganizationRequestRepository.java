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

    Optional<ChildOrganizationRequest> findFirstByChildIdAndOrganizationIdAndRequestedByAndStatus(
            Long childId, Long organizationId, Long requestedBy, ChildOrganizationRequestStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ChildOrganizationRequest r"
            + " where r.childId = :childId and r.organizationId = :organizationId"
            + " and r.status = com.itda.backend.domain.child.ChildOrganizationRequestStatus.PENDING"
            + " order by r.id")
    List<ChildOrganizationRequest> findPendingByChildIdAndOrganizationIdForUpdate(
            @Param("childId") Long childId, @Param("organizationId") Long organizationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ChildOrganizationRequest r"
            + " where r.childId = :childId and r.requestedBy = :requestedBy"
            + " and r.status = com.itda.backend.domain.child.ChildOrganizationRequestStatus.PENDING")
    List<ChildOrganizationRequest> findPendingByChildIdAndRequestedByForUpdate(
            @Param("childId") Long childId, @Param("requestedBy") Long requestedBy);

    List<ChildOrganizationRequest> findByOrganizationIdAndStatusOrderByCreatedAtAscIdAsc(
            Long organizationId, ChildOrganizationRequestStatus status);

    List<ChildOrganizationRequest> findByChildIdInAndStatusOrderByCreatedAtAscIdAsc(
            Collection<Long> childIds, ChildOrganizationRequestStatus status);
}
