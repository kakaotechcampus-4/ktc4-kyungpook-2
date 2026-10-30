package com.itda.backend.repository.insight;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.insight.InsightRun;
import com.itda.backend.domain.insight.InsightRunStatus;

public interface InsightRunRepository extends JpaRepository<InsightRun, Long> {

    /**
     * 그 아이·그 기간의 가장 최근 실행. 다음 판 번호의 기준이자 "이미 처리했나" 의 답이다.
     *
     * <p>{@code DONE} 이면 건너뛴다 — {@code insightCount} 가 0 이어도 마찬가지다. 낼 것이 없었다는
     * 뜻이지 못 낸 게 아니다 (§9.5).
     */
    Optional<InsightRun> findFirstByChildIdAndPeriodFromAndPeriodToOrderByRevisionDesc(
            Long childId, LocalDate periodFrom, LocalDate periodTo);

    /** 그 아이·그 기간의 실행 전부, 최근 판부터. 연속 실패 횟수를 센다 (#266). 한 기간에 몇 줄뿐이다. */
    List<InsightRun> findByChildIdAndPeriodFromAndPeriodToOrderByRevisionDesc(
            Long childId, LocalDate periodFrom, LocalDate periodTo);

    /**
     * 앱이 죽어 {@code RUNNING} 으로 남은 실행을 찾는다. 집기는 {@code FAILED} 만 다시 가져가므로,
     * 되돌리지 않으면 그 아동·기간은 <b>영영 처리되지 않는다</b>.
     */
    List<InsightRun> findByStatus(InsightRunStatus status);
}
