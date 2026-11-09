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

    /**
     * 연결·재연결·해제용. 해제된 행도 찾아서 되살릴 수 있게 {@code deletedAt} 조건을 걸지 않는다.
     * 잠근 뒤 살아 있는지 다시 봐야, 같은 보호자의 동시 재연결이 둘 다 성공하지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from ChildGuardian g where g.childId = :childId and g.userId = :userId")
    Optional<ChildGuardian> findByChildIdAndUserIdForUpdate(
            @Param("childId") Long childId, @Param("userId") Long userId);

    List<ChildGuardian> findByChildIdAndDeletedAtIsNullOrderByLinkedAtAsc(Long childId);

    /** 보호자의 아이 목록(G-10)용. 연결된 순서다 — 같은 시각이면 먼저 만든 행. */
    List<ChildGuardian> findByUserIdAndDeletedAtIsNullOrderByLinkedAtAscIdAsc(Long userId);

    /**
     * 탈퇴 정리용. 보호자에게 연결된 아이 id 만, id 순서로 읽는다. 연결 엔티티를 읽어 두지 않는다 — 읽어 두면 아이를 잠근 뒤의
     * 잠금 조회({@link #findByChildIdAndUserIdForUpdate})가 그 낡은 엔티티를 돌려줘, 그사이 기관 · 본인이 끊으며 남긴
     * {@code deleted_at} · {@code updated_at} 을 덮어쓴다.
     */
    @Query("select distinct g.childId from ChildGuardian g where g.userId = :userId and g.deletedAt is null order by g.childId")
    List<Long> findLinkedChildIds(@Param("userId") Long userId);

    /** 보호자 권한 확인용. 해제된 연결은 권한이 아니다. */
    boolean existsByChildIdAndUserIdAndDeletedAtIsNull(Long childId, Long userId);
}
