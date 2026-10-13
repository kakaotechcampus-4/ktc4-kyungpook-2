package com.itda.backend.repository.insight;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.insight.InsightStatus;

public interface InsightResultRepository extends JpaRepository<InsightResult, Long> {

    /** Gate 2 대기 목록. 조회 API 는 뒤 이슈에서 만들고, 여기서는 워커 테스트가 쓴다. */
    List<InsightResult> findByStatusOrderByIdDesc(InsightStatus status);
}
