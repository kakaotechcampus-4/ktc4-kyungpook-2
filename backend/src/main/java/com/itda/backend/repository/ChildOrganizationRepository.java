package com.itda.backend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.ChildOrganization;

public interface ChildOrganizationRepository extends JpaRepository<ChildOrganization, Long> {

    boolean existsByChildIdAndOrganizationIdAndDeletedAtIsNull(Long childId, Long organizationId);

    /** 재연결 전용. 연결 해제된 행도 찾아서 되살릴 수 있게 {@code deletedAt} 조건을 걸지 않는다. */
    Optional<ChildOrganization> findByChildIdAndOrganizationId(Long childId, Long organizationId);

    /** 해제된 행은 관리번호가 비워져 있으므로 deletedAt 조건 없이도 DB 유니크 제약과 같은 기준이다. */
    boolean existsByOrganizationIdAndExternalId(Long organizationId, String externalId);

    List<ChildOrganization> findByOrganizationIdAndDeletedAtIsNull(Long organizationId);
}
