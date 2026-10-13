package com.itda.backend.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.invitation.ChildGuardian;

public interface ChildGuardianRepository extends JpaRepository<ChildGuardian, Long> {

    /**
     * 연결·재연결·해제용. 해제된 행도 찾아서 되살릴 수 있게 {@code deletedAt} 조건을 걸지 않는다.
     * 잠근 뒤 살아 있는지 다시 봐야, 같은 보호자의 동시 재연결이 둘 다 성공하지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from ChildGuardian g where g.childId = :childId and g.userId = :userId")
    Optional<ChildGuardian> findByChildIdAndUserIdForUpdate(
            @Param("childId") Long childId, @Param("userId") Long userId);

    List<ChildGuardian> findByChildIdAndDeletedAtIsNullOrderByLinkedAtAsc(Long childId);
}
