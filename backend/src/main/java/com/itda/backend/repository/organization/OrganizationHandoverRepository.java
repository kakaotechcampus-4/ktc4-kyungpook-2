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

    /** 받은·취소된·만료된 코드도 센다. 행을 지우지 않으므로 유니크 제약과 같은 기준이다. */
    boolean existsByCode(String code);

    /** 잠그지 않고 읽는다. 가입은 발급한 담당자 행을 먼저 잠근 뒤 {@link #findByIdForUpdate} 로 이 행을 잠근다(잠금 순서). */
    Optional<OrganizationHandover> findByCode(String code);

    /** 수락용. 상태와 만료는 잠근 뒤 서비스가 판단한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from OrganizationHandover h where h.id = :id")
    Optional<OrganizationHandover> findByIdForUpdate(@Param("id") Long id);

    /**
     * 그 기관의 지금 쓸 수 있는 코드를 잠근다. 새로 발급하거나 취소할 때 거둬들인다. 담당자 행을 먼저 잠근 뒤 부른다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from OrganizationHandover h "
            + "where h.organizationId = :organizationId "
            + "and h.status = com.itda.backend.domain.organization.OrganizationHandoverStatus.PENDING "
            + "and h.expiresAt > :now order by h.id")
    List<OrganizationHandover> findLiveForUpdate(
            @Param("organizationId") Long organizationId, @Param("now") LocalDateTime now);
}
