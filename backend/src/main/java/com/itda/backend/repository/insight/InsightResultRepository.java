package com.itda.backend.repository.insight;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.insight.InsightStatus;

public interface InsightResultRepository extends JpaRepository<InsightResult, Long> {

    /** Gate 2 대기 목록. 조회 API 는 {@link #findByChildIdInOrderByIdDesc} 를 쓰고, 여기서는 워커 테스트가 쓴다. */
    List<InsightResult> findByStatusOrderByIdDesc(InsightStatus status);

    /**
     * 그 아이들의 인사이트 전부. Gate 2 목록이 <b>상태로 거르기 전에</b> 쓴다 — 회차마다 행이 쌓이는데,
     * 상태로 먼저 좁히면 최신 회차가 이미 승인된 경우 옛 회차의 대기 행을 최신으로 착각한다.
     *
     * <p>ponytail: 아이당 전 기간을 읽는다. 아이 한 명이 주마다 몇 건이라 MVP 규모에서는 가볍다.
     * 무거워지면 기간 하한을 받는 쿼리로 바꾼다.
     */
    List<InsightResult> findByChildIdInOrderByIdDesc(Collection<Long> childIds);

    /**
     * 그 아이의 한 상태 인사이트, 오래된 순. 새로 연결된 보호자에게 승인된 글을 열 때 쓴다 ({@code InsightAccessGranter}).
     * 오래된 글부터 넣어 권한 행 id 도 오래된 순이 되게 한다.
     */
    List<InsightResult> findByChildIdAndStatusOrderByIdAsc(Long childId, InsightStatus status);

    /**
     * 그 아이·그 기간의 마지막 회차 번호. 옛 회차를 승인해 철 지난 글이 다른 기관으로 나가는 것을 막는 데 쓴다.
     * 인사이트가 한 건도 없으면 {@code null} 이다.
     */
    @Query("select max(i.revision) from InsightResult i "
            + "where i.childId = :childId and i.periodFrom = :periodFrom and i.periodTo = :periodTo")
    Integer findMaxRevision(@Param("childId") Long childId,
            @Param("periodFrom") LocalDate periodFrom, @Param("periodTo") LocalDate periodTo);

    /**
     * Gate 2 결정용 잠금 조회. 같은 인사이트에 승인과 보류가 동시에 오면 한쪽만 반영되게 한다 —
     * 잠그지 않으면 둘 다 {@code GATE2_PENDING} 을 읽고 둘 다 성공한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InsightResult i where i.id = :id")
    Optional<InsightResult> findByIdForUpdate(@Param("id") Long id);
}
