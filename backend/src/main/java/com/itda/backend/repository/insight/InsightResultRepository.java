package com.itda.backend.repository.insight;

import java.time.LocalDate;
import java.time.LocalDateTime;
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

    String LATEST_OF_CHILDREN = "(select r.id as id, r.childId as childId, r.periodFrom as periodFrom, "
            + "r.periodTo as periodTo, r.revision as revision, r.status as status, "
            + "r.sourceInstitutionIds as sourceInstitutionIds, "
            + "max(r.revision) over (partition by r.childId, r.periodFrom, r.periodTo) as latest "
            + "from InsightResult r where r.childId in :childIds) x";

    String LATEST_OF_PERIODS = "(select r.id as id, r.childId as childId, r.periodFrom as periodFrom, "
            + "r.periodTo as periodTo, r.revision as revision, r.status as status, "
            + "r.sourceInstitutionIds as sourceInstitutionIds, "
            + "max(r.revision) over (partition by r.childId, r.periodFrom, r.periodTo) as latest "
            + "from InsightResult r where r.childId in :childIds "
            + "and r.periodFrom in :periodFroms and r.periodTo in :periodTos) x";

    String KEY = "new com.itda.backend.repository.insight.InsightKey("
            + "x.id, x.childId, x.periodFrom, x.periodTo, x.revision, x.status, x.sourceInstitutionIds)";

    @Query("select i from InsightResult i where i.id in (select x.id from " + LATEST_OF_CHILDREN
            + " where x.revision = x.latest and x.status = :status) order by i.id desc")
    List<InsightResult> findLatestByChildIdInAndStatus(@Param("childIds") Collection<Long> childIds,
            @Param("status") InsightStatus status);

    @Query("select " + KEY + " from " + LATEST_OF_CHILDREN
            + " where x.revision = x.latest and x.status = :status order by x.id desc")
    List<InsightKey> findLatestKeysByChildIdInAndStatus(@Param("childIds") Collection<Long> childIds,
            @Param("status") InsightStatus status);

    @Query("select " + KEY + " from " + LATEST_OF_PERIODS + " where x.revision = x.latest order by x.id desc")
    List<InsightKey> findLatestKeysForPeriods(@Param("childIds") Collection<Long> childIds,
            @Param("periodFroms") Collection<LocalDate> periodFroms,
            @Param("periodTos") Collection<LocalDate> periodTos);

    List<InsightResult> findByChildIdInOrderByIdDesc(Collection<Long> childIds);

    List<InsightResult> findByChildIdAndStatusOrderByIdAsc(Long childId, InsightStatus status);

    @Query("select max(i.revision) from InsightResult i "
            + "where i.childId = :childId and i.periodFrom = :periodFrom and i.periodTo = :periodTo")
    Integer findMaxRevision(@Param("childId") Long childId,
            @Param("periodFrom") LocalDate periodFrom, @Param("periodTo") LocalDate periodTo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InsightResult i where i.id = :id")
    Optional<InsightResult> findByIdForUpdate(@Param("id") Long id);

    List<InsightResult> findByChildIdInAndCreatedAtGreaterThanEqual(Collection<Long> childIds, LocalDateTime since);

    @Query("select distinct new com.itda.backend.repository.insight.ApprovedInsightPeriod("
            + "i.childId, i.periodFrom, i.periodTo) "
            + "from InsightResult i where i.status = :status and i.updatedAt >= :since")
    List<ApprovedInsightPeriod> findApprovedPeriodsSince(@Param("status") InsightStatus status,
            @Param("since") LocalDateTime since);
}
