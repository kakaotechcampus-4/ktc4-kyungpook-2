package com.itda.backend.repository.organization;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.organization.Organization;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    boolean existsByBusinessNumber(String businessNumber);

    boolean existsByCode(String code);

    Optional<Organization> findByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Organization o where o.id = :id")
    Optional<Organization> findByIdForUpdate(@Param("id") Long id);

    default Organization lockOwnOrganization(Long id) {
        return findByIdForUpdate(id).orElseThrow(() -> new IllegalStateException("소속 기관 행이 없습니다: " + id));
    }
}
