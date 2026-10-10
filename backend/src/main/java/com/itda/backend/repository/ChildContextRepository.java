package com.itda.backend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.ChildContext;

public interface ChildContextRepository extends JpaRepository<ChildContext, Long> {

    /**
     * 아동별 승인본을 <b>최근 승인 순</b>으로 가져온다.
     *
     * <p>관찰 날짜 순이 아니다 — 8월 기록을 10월에 승인하면 뒤늦게 맨 앞에 온다. 타임라인 화면은
     * 날짜로 늘어놓으므로, 조회 API(O-14)가 {@code summary_result.entry_date} 로 다시 정렬해야 한다.
     * 날짜·기관·판수도 {@code summary_result_id} 로 조인해 채운다.
     */
    List<ChildContext> findByChildIdOrderByIdDesc(Long childId);
}
