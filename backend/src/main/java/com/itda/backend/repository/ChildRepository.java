package com.itda.backend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.Child;

public interface ChildRepository extends JpaRepository<Child, Long> {

    Optional<Child> findByIdAndDeletedAtIsNull(Long id);

    // FK/연관관계 없이(§16 원칙) child_organization을 평범한 Long 컬럼으로만 이어서 조회한다.
    @Query("select c from Child c, ChildOrganization co "
            + "where co.childId = c.id and co.organizationId = :organizationId "
            + "and co.deletedAt is null and c.deletedAt is null")
    List<Child> findActiveByOrganizationId(@Param("organizationId") Long organizationId);
}
