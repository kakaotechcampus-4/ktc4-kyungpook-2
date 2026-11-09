package com.itda.backend.repository.child;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.child.ChildOrganization;

public interface ChildOrganizationRepository extends JpaRepository<ChildOrganization, Long> {

    boolean existsByChildIdAndOrganizationIdAndDeletedAtIsNull(Long childId, Long organizationId);

    Optional<ChildOrganization> findByChildIdAndOrganizationId(Long childId, Long organizationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select co from ChildOrganization co where co.childId = :childId and co.organizationId = :organizationId")
    Optional<ChildOrganization> findByChildIdAndOrganizationIdForUpdate(
            @Param("childId") Long childId, @Param("organizationId") Long organizationId);

    boolean existsByOrganizationIdAndExternalId(Long organizationId, String externalId);

    List<ChildOrganization> findByOrganizationIdAndDeletedAtIsNull(Long organizationId);

    List<ChildOrganization> findByChildIdAndDeletedAtIsNull(Long childId);

    List<ChildOrganization> findByChildIdInAndDeletedAtIsNull(Collection<Long> childIds);

    default Map<Long, List<Long>> findOrganizationIdsByChildIdIn(Collection<Long> childIds) {
        return findByChildIdInAndDeletedAtIsNull(childIds).stream()
                .collect(Collectors.groupingBy(ChildOrganization::getChildId,
                        Collectors.mapping(ChildOrganization::getOrganizationId, Collectors.toList())));
    }
}
