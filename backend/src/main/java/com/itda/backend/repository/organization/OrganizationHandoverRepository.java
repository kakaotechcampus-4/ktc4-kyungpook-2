package com.itda.backend.repository.organization;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.organization.OrganizationHandover;

public interface OrganizationHandoverRepository extends JpaRepository<OrganizationHandover, Long> {

    boolean existsByCode(String code);

    Optional<OrganizationHandover> findByCode(String code);

    @Query("select new com.itda.backend.repository.organization.HandoverTarget(h.id, h.organizationId, h.issuedBy) "
            + "from OrganizationHandover h where h.code = :code")
    Optional<HandoverTarget> findTargetByCode(@Param("code") String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from OrganizationHandover h where h.id = :id")
    Optional<OrganizationHandover> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from OrganizationHandover h "
            + "where h.organizationId = :organizationId "
            + "and h.status = com.itda.backend.domain.organization.OrganizationHandoverStatus.PENDING "
            + "and h.expiresAt > :now order by h.id")
    List<OrganizationHandover> findLiveForUpdate(
            @Param("organizationId") Long organizationId, @Param("now") LocalDateTime now);
}
