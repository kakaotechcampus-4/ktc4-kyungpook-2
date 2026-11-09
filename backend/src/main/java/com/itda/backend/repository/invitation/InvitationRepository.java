package com.itda.backend.repository.invitation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.invitation.Invitation;

public interface InvitationRepository extends JpaRepository<Invitation, Long> {

    boolean existsByCode(String code);

    @Query("select new com.itda.backend.repository.invitation.InvitationTarget(i.id, i.childId, i.organizationId) "
            + "from Invitation i where i.code = :code")
    Optional<InvitationTarget> findTargetByCode(@Param("code") String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invitation i where i.id = :id")
    Optional<Invitation> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invitation i "
            + "where i.id = :id and i.childId = :childId and i.organizationId = :organizationId")
    Optional<Invitation> findByIdAndChildIdAndOrganizationIdForUpdate(
            @Param("id") Long id, @Param("childId") Long childId, @Param("organizationId") Long organizationId);

    @Query("select i from Invitation i "
            + "where i.childId = :childId and i.organizationId = :organizationId "
            + "and i.status = com.itda.backend.domain.invitation.InvitationStatus.PENDING and i.expiresAt > :now "
            + "order by i.createdAt desc, i.id desc")
    List<Invitation> findLive(
            @Param("childId") Long childId, @Param("organizationId") Long organizationId, @Param("now") LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invitation i "
            + "where i.childId = :childId and i.organizationId = :organizationId "
            + "and i.status = com.itda.backend.domain.invitation.InvitationStatus.PENDING and i.expiresAt > :now "
            + "order by i.id")
    List<Invitation> findLiveForUpdate(
            @Param("childId") Long childId, @Param("organizationId") Long organizationId, @Param("now") LocalDateTime now);
}
