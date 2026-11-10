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

    List<ChildContext> findByChildIdOrderByIdDesc(Long childId);

    List<ChildContext> findTop5ByChildIdOrderByIdDesc(Long childId);

    List<ChildContext> findBySummaryResultIdIn(Collection<Long> summaryResultIds);

    @Query("select max(c.id) from ChildContext c "
            + "where c.childId = :childId and c.summaryResultId in "
            + "(select s.id from SummaryResult s "
            + "where s.childId = :childId and s.entryDate between :from and :to)")
    Long findMaxIdInPeriod(@Param("childId") Long childId,
            @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select distinct new com.itda.backend.repository.summary.ApprovedEntryDay(c.childId, s.entryDate) "
            + "from ChildContext c, SummaryResult s "
            + "where s.id = c.summaryResultId and c.createdAt >= :since")
    List<ApprovedEntryDay> findApprovedEntryDaysSince(@Param("since") LocalDateTime since);

    @Query("select distinct new com.itda.backend.repository.summary.ApprovedEntryDay(c.childId, s.entryDate) "
            + "from ChildContext c, SummaryResult s "
            + "where s.id = c.summaryResultId and s.institutionId = :institutionId and c.createdAt >= :since")
    List<ApprovedEntryDay> findApprovedEntryDaysSinceByInstitution(@Param("institutionId") Long institutionId,
            @Param("since") LocalDateTime since);
}
