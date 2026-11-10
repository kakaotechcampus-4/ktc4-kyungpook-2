package com.itda.backend.repository.child;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.child.Child;

public interface ChildRepository extends JpaRepository<Child, Long> {

    Optional<Child> findByIdAndDeletedAtIsNull(Long id);

    List<Child> findByIdInAndDeletedAtIsNull(Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Child c where c.id = :id")
    Optional<Child> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select c from Child c where c.id = :id")
    Optional<Child> findByIdForShare(@Param("id") Long id);

    @Query("select c from Child c, ChildOrganization co "
            + "where co.childId = c.id and co.organizationId = :organizationId "
            + "and co.deletedAt is null and c.deletedAt is null")
    List<Child> findByOrganizationId(@Param("organizationId") Long organizationId);

    @Query("select count(c) > 0 from Child c, ChildOrganization co "
            + "where co.childId = c.id and co.organizationId = :organizationId "
            + "and co.deletedAt is null and c.deletedAt is null "
            + "and c.name = :name and c.birthdate = :birthdate")
    boolean existsInOrganization(@Param("organizationId") Long organizationId,
            @Param("name") String name, @Param("birthdate") LocalDate birthdate);

    @Query("select c from Child c, ChildOrganization co "
            + "where co.childId = c.id and co.organizationId = :organizationId "
            + "and co.deletedAt is null and c.deletedAt is null "
            + "and exists (select sc.id from SharingConsent sc where sc.childId = c.id "
            + "and sc.organizationId = :organizationId "
            + "and sc.status = com.itda.backend.domain.consent.SharingConsentStatus.ACTIVE)")
    List<Child> findConsentedByOrganizationId(@Param("organizationId") Long organizationId);
}
