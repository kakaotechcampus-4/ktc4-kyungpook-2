package com.itda.backend.repository.insight;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.insight.InsightRun;
import com.itda.backend.domain.insight.InsightRunStatus;

public interface InsightRunRepository extends JpaRepository<InsightRun, Long> {

    Optional<InsightRun> findFirstByChildIdAndPeriodFromAndPeriodToOrderByRevisionDesc(
            Long childId, LocalDate periodFrom, LocalDate periodTo);

    List<InsightRun> findByChildIdAndPeriodFromAndPeriodToOrderByRevisionDesc(
            Long childId, LocalDate periodFrom, LocalDate periodTo);

    List<InsightRun> findByStatus(InsightRunStatus status);

    List<InsightRun> findByChildIdInAndPeriodToGreaterThanEqual(Collection<Long> childIds, LocalDate periodTo);
}
