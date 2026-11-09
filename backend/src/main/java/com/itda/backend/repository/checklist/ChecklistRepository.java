package com.itda.backend.repository.checklist;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.checklist.Checklist;

public interface ChecklistRepository extends JpaRepository<Checklist, Long> {

    @Query("select c from Checklist c where c.organizationId = :organizationId and c.canceledAt is null "
            + "order by c.createdAt desc, c.id desc")
    List<Checklist> findActivePage(@Param("organizationId") Long organizationId, Pageable pageable);

    @Query("select c from Checklist c where c.organizationId = :organizationId and c.canceledAt is null "
            + "and (c.createdAt < :createdAt or (c.createdAt = :createdAt and c.id < :id)) "
            + "order by c.createdAt desc, c.id desc")
    List<Checklist> findActivePageBefore(@Param("organizationId") Long organizationId,
            @Param("createdAt") LocalDateTime createdAt, @Param("id") Long id, Pageable pageable);

    @Query("select c.createdAt from Checklist c where c.id = :id and c.organizationId = :organizationId")
    Optional<LocalDateTime> findCreatedAtOf(@Param("id") Long id, @Param("organizationId") Long organizationId);

    boolean existsByIdAndOrganizationId(Long id, Long organizationId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select c from Checklist c where c.id = :id")
    Optional<Checklist> findByIdForShare(@Param("id") Long id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Checklist c set c.canceledAt = :at "
            + "where c.id = :id and c.organizationId = :organizationId and c.canceledAt is null")
    int cancelIfActive(@Param("id") Long id, @Param("organizationId") Long organizationId,
            @Param("at") LocalDateTime at);
}
