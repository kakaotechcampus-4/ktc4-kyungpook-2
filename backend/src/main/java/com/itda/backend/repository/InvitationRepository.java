package com.itda.backend.repository;

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

    /** 수락·취소·만료된 코드도 센다. 행을 지우지 않으므로 유니크 제약과 같은 기준이다. */
    boolean existsByCode(String code);

    /** 잠그지 않고 id 만 가져온다 — {@link InvitationTarget} 참고. */
    @Query("select new com.itda.backend.repository.InvitationTarget(i.id, i.childId, i.organizationId) "
            + "from Invitation i where i.code = :code")
    Optional<InvitationTarget> findTargetByCode(@Param("code") String code);

    /** 수락용. 상태와 만료는 잠근 뒤 서비스가 판단한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invitation i where i.id = :id")
    Optional<Invitation> findByIdForUpdate(@Param("id") Long id);

    /** 취소용. 다른 아이·다른 기관의 초대는 찾지 않는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invitation i "
            + "where i.id = :id and i.childId = :childId and i.organizationId = :organizationId")
    Optional<Invitation> findByIdAndChildIdAndOrganizationIdForUpdate(
            @Param("id") Long id, @Param("childId") Long childId, @Param("organizationId") Long organizationId);

    /** 지금 쓸 수 있는 코드만, 최근 발급 순으로. */
    @Query("select i from Invitation i "
            + "where i.childId = :childId and i.organizationId = :organizationId "
            + "and i.status = com.itda.backend.domain.invitation.InvitationStatus.PENDING and i.expiresAt > :now "
            + "order by i.createdAt desc, i.id desc")
    List<Invitation> findLive(
            @Param("childId") Long childId, @Param("organizationId") Long organizationId, @Param("now") LocalDateTime now);
}
