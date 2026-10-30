package com.itda.backend.repository.organization;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.organization.Organization;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    /** 기관 1곳당 계정 1개 — 같은 사업자등록번호로 두 번 가입하지 못하게 먼저 확인한다. */
    boolean existsByBusinessNumber(String businessNumber);

    boolean existsByCode(String code);

    Optional<Organization> findByCode(String code);

    /** 기관 코드를 처음 채울 때. 동시에 두 번 열어도 코드가 한 번만 붙도록 잠근 뒤 비어 있는지 본다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Organization o where o.id = :id")
    Optional<Organization> findByIdForUpdate(@Param("id") Long id);
}
