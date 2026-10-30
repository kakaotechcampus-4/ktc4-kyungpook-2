package com.itda.backend.repository.summary;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.summary.ChildContext;

public interface ChildContextRepository extends JpaRepository<ChildContext, Long> {

    /**
     * 아동별 승인본을 <b>최근 승인 순</b>으로 가져온다.
     *
     * <p>관찰 날짜 순이 아니다 — 8월 기록을 10월에 승인하면 뒤늦게 맨 앞에 온다. 타임라인 화면은
     * 날짜로 늘어놓으므로, 조회 API(기관 타임라인 O-14 · 보호자 일지 G-20)가 {@code summary_result.entry_date} 로 다시
     * 정렬해야 한다. 날짜·기관도 {@code summary_result_id} 로 조인해 채운다 — 기관 타임라인은 그 기관으로 거른다.
     */
    List<ChildContext> findByChildIdOrderByIdDesc(Long childId);

    /** 아동별 승인본 중 <b>최근 승인 5건</b>. 보호자 홈의 "최근 공유 활동" 이 쓴다. */
    List<ChildContext> findTop5ByChildIdOrderByIdDesc(Long childId);

    /**
     * 주어진 요약들 중 <b>승인된 것</b>. 행이 있다는 것이 곧 Gate 1 통과다.
     *
     * <p>인사이트가 기간치 요약을 먼저 고른 뒤 그중 승인된 것만 남기는 데 쓴다. 반대로
     * {@link #findByChildIdOrderByIdDesc} 로 시작하면 그 아이의 승인 이력 전체를 올려 놓고
     * 자바에서 한 주치만 남기게 된다 — 이 테이블은 지우지 않고 쌓이기만 한다.
     */
    List<ChildContext> findBySummaryResultIdIn(Collection<Long> summaryResultIds);

    /**
     * 그 아동·그 기간에 승인된 것 중 <b>가장 큰 id</b>. 인사이트가 "재료가 늘었나" 를 보는 눈금이다 (§9.5).
     *
     * <p>이 테이블은 지우지 않고 쌓이기만 해서 id 가 단조 증가한다. 새 승인이 생기면 반드시 더 큰 id 가
     * 생기므로, 값 하나를 지난 실행의 것과 견주면 된다. 승인이 하나도 없으면 {@code null} 이다.
     *
     * <p>기간은 {@code summary_result.entry_date} 에 있어서 서브쿼리로 좁힌다 — 워커가 아이마다
     * 틱마다 부르는 자리라 조회 한 번으로 끝내려는 것이다.
     */
    @Query("select max(c.id) from ChildContext c "
            + "where c.childId = :childId and c.summaryResultId in "
            + "(select s.id from SummaryResult s "
            + "where s.childId = :childId and s.entryDate between :from and :to)")
    Long findMaxIdInPeriod(@Param("childId") Long childId,
            @Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * 그때 이후 Gate 1 을 통과한 요약들의 (아이, 관찰 날짜). 인사이트 워커가 <b>어느 주를 다시 볼지</b> 고른다 (#239).
     *
     * <p>관찰 날짜가 아니라 <b>승인 시각</b>으로 고르는 이유 — 밀린 기록을 늦게 승인해도 그 주를 다시 보려고다.
     * 관찰 날짜로 "지난주" 만 보면, 2주 지나 승인한 기록은 영영 재료가 안 된다.
     */
    @Query("select distinct new com.itda.backend.repository.summary.ApprovedEntryDay(c.childId, s.entryDate) "
            + "from ChildContext c, SummaryResult s "
            + "where s.id = c.summaryResultId and c.createdAt >= :since")
    List<ApprovedEntryDay> findApprovedEntryDaysSince(@Param("since") LocalDateTime since);
}
