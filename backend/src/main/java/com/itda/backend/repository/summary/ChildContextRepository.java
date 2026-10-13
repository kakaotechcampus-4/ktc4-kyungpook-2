package com.itda.backend.repository.summary;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.summary.ChildContext;

public interface ChildContextRepository extends JpaRepository<ChildContext, Long> {

    /**
     * 아동별 승인본을 <b>최근 승인 순</b>으로 가져온다.
     *
     * <p>관찰 날짜 순이 아니다 — 8월 기록을 10월에 승인하면 뒤늦게 맨 앞에 온다. 타임라인 화면은
     * 날짜로 늘어놓으므로, 조회 API(O-14)가 {@code summary_result.entry_date} 로 다시 정렬해야 한다.
     * 날짜·기관·판수도 {@code summary_result_id} 로 조인해 채운다.
     */
    List<ChildContext> findByChildIdOrderByIdDesc(Long childId);

    /**
     * 주어진 요약들 중 <b>승인된 것</b>. 행이 있다는 것이 곧 Gate 1 통과다.
     *
     * <p>인사이트가 기간치 요약을 먼저 고른 뒤 그중 승인된 것만 남기는 데 쓴다. 반대로
     * {@link #findByChildIdOrderByIdDesc} 로 시작하면 그 아이의 승인 이력 전체를 올려 놓고
     * 자바에서 한 주치만 남기게 된다 — 이 테이블은 지우지 않고 쌓이기만 한다.
     */
    List<ChildContext> findBySummaryResultIdIn(Collection<Long> summaryResultIds);
}
