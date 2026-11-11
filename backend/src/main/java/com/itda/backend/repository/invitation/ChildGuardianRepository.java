package com.itda.backend.repository.invitation;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.invitation.ChildGuardian;

public interface ChildGuardianRepository extends JpaRepository<ChildGuardian, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from ChildGuardian g where g.childId = :childId and g.userId = :userId")
    Optional<ChildGuardian> findByChildIdAndUserIdForUpdate(
            @Param("childId") Long childId, @Param("userId") Long userId);

    List<ChildGuardian> findByChildIdAndDeletedAtIsNullOrderByLinkedAtAsc(Long childId);

    List<ChildGuardian> findByUserIdAndDeletedAtIsNullOrderByLinkedAtAscIdAsc(Long userId);

    @Query("select distinct g.childId from ChildGuardian g where g.userId = :userId and g.deletedAt is null order by g.childId")
    List<Long> findLinkedChildIds(@Param("userId") Long userId);

    boolean existsByChildIdAndUserIdAndDeletedAtIsNull(Long childId, Long userId);
}
