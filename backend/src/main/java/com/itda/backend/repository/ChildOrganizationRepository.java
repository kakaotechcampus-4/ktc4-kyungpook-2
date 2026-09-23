package com.itda.backend.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.ChildOrganization;

public interface ChildOrganizationRepository extends JpaRepository<ChildOrganization, Long> {

    boolean existsByChildIdAndOrganizationIdAndDeletedAtIsNull(Long childId, Long organizationId);

    /** 재연결 전용. 연결 해제된 행도 찾아서 되살릴 수 있게 {@code deletedAt} 조건을 걸지 않는다. */
    Optional<ChildOrganization> findByChildIdAndOrganizationId(Long childId, Long organizationId);
}
