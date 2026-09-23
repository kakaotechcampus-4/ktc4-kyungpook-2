package com.itda.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.ChildOrganization;

public interface ChildOrganizationRepository extends JpaRepository<ChildOrganization, Long> {

    boolean existsByChildIdAndOrganizationIdAndDeletedAtIsNull(Long childId, Long organizationId);
}
