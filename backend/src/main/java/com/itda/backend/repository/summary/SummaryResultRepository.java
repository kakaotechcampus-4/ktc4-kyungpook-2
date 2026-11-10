package com.itda.backend.repository.summary;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.domain.summary.SummaryStatus;

import jakarta.persistence.LockModeType;

public interface SummaryResultRepository extends JpaRepository<SummaryResult, Long> {

    Optional<SummaryResult> findFirstByChildIdAndEntryDateAndInstitutionIdOrderByRevisionDesc(
            Long childId, LocalDate entryDate, Long institutionId);

    List<SummaryResult> findByChildIdAndEntryDateBetween(Long childId, LocalDate from, LocalDate to);

    List<SummaryResult> findByChildIdIn(Collection<Long> childIds);

    @Query("select count(s) > 0 from SummaryResult s "
            + "where s.childId = :childId and s.entryDate = :entryDate and s.institutionId = :institutionId "
            + "and s.revision > :revision")
    boolean existsNewerRevision(@Param("childId") Long childId, @Param("entryDate") LocalDate entryDate,
            @Param("institutionId") Long institutionId, @Param("revision") Integer revision);

    @Query("select new com.itda.backend.repository.summary.LatestRevision(s.childId, s.entryDate, max(s.revision)) "
            + "from SummaryResult s "
            + "where s.institutionId = :institutionId and s.childId in :childIds and s.entryDate in :entryDates "
            + "group by s.childId, s.entryDate")
    List<LatestRevision> findLatestRevisions(@Param("institutionId") Long institutionId,
            @Param("childIds") Collection<Long> childIds, @Param("entryDates") Collection<LocalDate> entryDates);

    @Query("select s from SummaryResult s where s.institutionId = :institutionId and s.status = :status "
            + "order by s.entryDate, s.id")
    List<SummaryResult> findGate1List(@Param("institutionId") Long institutionId,
            @Param("status") SummaryStatus status);

    @Query("select new com.itda.backend.repository.summary.Gate1Key("
            + "s.id, s.childId, s.entryDate, s.revision, s.status, s.noContent) "
            + "from SummaryResult s where s.institutionId = :institutionId and s.status in :statuses "
            + "order by s.entryDate, s.id")
    List<Gate1Key> findGate1Keys(@Param("institutionId") Long institutionId,
            @Param("statuses") Collection<SummaryStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SummaryResult s "
            + "where s.childId = :childId and s.entryDate = :entryDate and s.institutionId = :institutionId "
            + "order by s.revision desc")
    List<SummaryResult> findGroupForUpdate(@Param("childId") Long childId, @Param("entryDate") LocalDate entryDate,
            @Param("institutionId") Long institutionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SummaryResult s where s.id = :id and s.institutionId = :institutionId")
    Optional<SummaryResult> findByIdAndInstitutionIdForUpdate(@Param("id") Long id,
            @Param("institutionId") Long institutionId);

    @Query("select count(s) from SummaryResult s where s.institutionId = :institutionId "
            + "and (s.createdAt >= :since or (s.status = :pending and s.updatedAt >= :since))")
    long countGeneratedSince(@Param("institutionId") Long institutionId, @Param("pending") SummaryStatus pending,
            @Param("since") LocalDateTime since);
}
