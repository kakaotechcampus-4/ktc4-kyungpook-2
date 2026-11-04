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

    /** 기관의 살아 있는 요청, 최신순. 쪽 크기는 부르는 쪽이 정한다. */
    @Query("select c from Checklist c where c.organizationId = :organizationId and c.canceledAt is null "
            + "order by c.createdAt desc, c.id desc")
    List<Checklist> findActivePage(@Param("organizationId") Long organizationId, Pageable pageable);

    /** 기준 요청 {@code (createdAt, id)} 보다 오래된 살아 있는 요청, 최신순 — "이전 요청 더 보기". */
    @Query("select c from Checklist c where c.organizationId = :organizationId and c.canceledAt is null "
            + "and (c.createdAt < :createdAt or (c.createdAt = :createdAt and c.id < :id)) "
            + "order by c.createdAt desc, c.id desc")
    List<Checklist> findActivePageBefore(@Param("organizationId") Long organizationId,
            @Param("createdAt") LocalDateTime createdAt, @Param("id") Long id, Pageable pageable);

    /**
     * 이어 보기 기준 요청의 보낸 시각. <b>내린 요청이어도 읽는다</b> — 첫 쪽 마지막 요청을 내린 뒤에도 다음 쪽이 열려야 한다.
     * 다른 기관 요청이면 비어 있다.
     */
    @Query("select c.createdAt from Checklist c where c.id = :id and c.organizationId = :organizationId")
    Optional<LocalDateTime> findCreatedAtOf(@Param("id") Long id, @Param("organizationId") Long organizationId);

    boolean existsByIdAndOrganizationId(Long id, Long organizationId);

    /**
     * 보호자 확인용 공유 잠금. 내리기({@link #cancelIfActive})는 같은 행을 UPDATE 하므로 이 잠금과 줄을 선다 — 내리기가 먼저
     * 커밋되면 확인은 내린 요청을 보고, 확인이 먼저면 내리기는 확인이 끝난 뒤 내린다. 확인끼리는 서로 막지 않는다.
     *
     * <p>이 트랜잭션에서 이 행을 처음 읽는 호출이어야 한다. 미리 읽어 두면 영속성 컨텍스트가 잠그기 전 값을 돌려준다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select c from Checklist c where c.id = :id")
    Optional<Checklist> findByIdForShare(@Param("id") Long id);

    /**
     * 내린다. <b>처음 한 번만</b> — 이미 내렸으면 0행이고 처음 시각이 남는다. 두 요청이 겹치면 뒤의 UPDATE 가 앞선 커밋을
     * 기다렸다 조건을 다시 보고 0행이 된다. 다른 기관 요청도 0행이므로 부르는 쪽이 {@link #existsByIdAndOrganizationId} 로 가린다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Checklist c set c.canceledAt = :at "
            + "where c.id = :id and c.organizationId = :organizationId and c.canceledAt is null")
    int cancelIfActive(@Param("id") Long id, @Param("organizationId") Long organizationId,
            @Param("at") LocalDateTime at);
}
