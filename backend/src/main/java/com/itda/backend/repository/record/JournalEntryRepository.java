package com.itda.backend.repository.record;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;

import jakarta.persistence.LockModeType;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {

    @Query("""
            select e from JournalEntry e
            where e.deletedAt < :cutoff
              and e.summaryId in (select s.id from SummaryResult s
                                  where s.status = com.itda.backend.domain.summary.SummaryStatus.REMOVED)
            order by e.id
            """)
    List<JournalEntry> findRemovedAtGate1Before(@Param("cutoff") LocalDateTime cutoff, Limit limit);

    boolean existsBySummaryId(Long summaryId);

    boolean existsByRawRecordId(Long rawRecordId);

    List<JournalEntry> findByRawRecordIdInAndDeletedAtIsNullOrderByIdAsc(Collection<Long> rawRecordIds);


    Optional<JournalEntry> findByIdAndDeletedAtIsNull(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from JournalEntry e where e.id = :id and e.deletedAt is null")
    Optional<JournalEntry> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select count(e) > 0 from JournalEntry e, RawRecord r
            where e.id = :id and e.deletedAt is null
              and r.id = e.rawRecordId and r.deletedAt is null and r.institutionId = :institutionId
            """)
    boolean existsOwnedBy(@Param("id") Long id, @Param("institutionId") String institutionId);

    @Query("""
            select count(e) from JournalEntry e, RawRecord r
            where r.id = e.rawRecordId and r.institutionId = :institutionId and r.deletedAt is null
              and e.deletedAt is null and e.status in :statuses
            """)
    long countByInstitutionAndStatusIn(@Param("institutionId") String institutionId,
            @Param("statuses") Collection<JournalEntryStatus> statuses);

    @Query("select e from JournalEntry e "
            + "where e.status = :status and e.rawRecordId is not null and e.deletedAt is null "
            + "order by e.id")
    List<JournalEntry> findMatchingTargets(@Param("status") JournalEntryStatus status, Limit limit);

    @Query("select e from JournalEntry e "
            + "where e.status = :status and e.childId is not null and e.deletedAt is null "
            + "order by e.id")
    List<JournalEntry> findValidationTargets(@Param("status") JournalEntryStatus status, Limit limit);

    List<JournalEntry> findByStatusAndDeletedAtIsNull(JournalEntryStatus status);

    @Query("select new com.itda.backend.repository.record.SummaryCandidate("
            + "e.id, e.childId, e.entryDate, r.institutionId, e.createdAt) "
            + "from JournalEntry e join RawRecord r on r.id = e.rawRecordId "
            + "where e.status in :statuses and e.childId is not null and e.entryDate is not null "
            + "and e.deletedAt is null "
            + "order by e.id")
    List<SummaryCandidate> findSummaryCandidates(@Param("statuses") Collection<JournalEntryStatus> statuses);

    @Query("select e.id from JournalEntry e join RawRecord r on r.id = e.rawRecordId "
            + "where e.status = :status and e.childId = :childId and e.entryDate = :entryDate "
            + "and r.institutionId = :institutionId and e.deletedAt is null "
            + "order by e.id")
    List<Long> findGroupEntryIds(@Param("status") JournalEntryStatus status, @Param("childId") Long childId,
            @Param("entryDate") LocalDate entryDate, @Param("institutionId") String institutionId);

    @Query("select e from JournalEntry e join RawRecord r on r.id = e.rawRecordId "
            + "where e.status = :status and r.institutionId = :institutionId and e.childId is not null "
            + "and e.deletedAt is null "
            + "order by e.entryDate, e.childId, e.id")
    List<JournalEntry> findByInstitutionAndStatus(@Param("institutionId") String institutionId,
            @Param("status") JournalEntryStatus status);

    @Query("select e.id from JournalEntry e "
            + "where e.summaryId = :summaryId and e.status = :status and e.deletedAt is null "
            + "order by e.id")
    List<Long> findIdsBySummaryIdAndStatus(@Param("summaryId") Long summaryId,
            @Param("status") JournalEntryStatus status);

    List<JournalEntry> findBySummaryIdAndDeletedAtIsNull(Long summaryId);

    @Query("select e from JournalEntry e where e.summaryId in :summaryIds and e.deletedAt is null order by e.id")
    List<JournalEntry> findLiveBySummaryIds(@Param("summaryIds") Collection<Long> summaryIds);

    @Query("select count(e) > 0 from JournalEntry e join RawRecord r on r.id = e.rawRecordId "
            + "where e.status in :statuses and r.institutionId = :institutionId and e.entryDate = :entryDate "
            + "and e.deletedAt is null")
    boolean existsInProgress(@Param("statuses") Collection<JournalEntryStatus> statuses,
            @Param("institutionId") String institutionId, @Param("entryDate") LocalDate entryDate);

    @Query("select distinct new com.itda.backend.repository.record.GroupKey(e.childId, e.entryDate) "
            + "from JournalEntry e join RawRecord r on r.id = e.rawRecordId "
            + "where e.status in :statuses and r.institutionId = :institutionId and e.entryDate in :entryDates "
            + "and e.childId is not null and e.deletedAt is null")
    List<GroupKey> findGroupKeys(@Param("statuses") Collection<JournalEntryStatus> statuses,
            @Param("institutionId") String institutionId, @Param("entryDates") Collection<LocalDate> entryDates);

    @Query("select distinct e.entryDate from JournalEntry e join RawRecord r on r.id = e.rawRecordId "
            + "where e.status in :statuses and r.institutionId = :institutionId and e.entryDate in :entryDates "
            + "and e.deletedAt is null")
    List<LocalDate> findInProgressDates(@Param("statuses") Collection<JournalEntryStatus> statuses,
            @Param("institutionId") String institutionId, @Param("entryDates") Collection<LocalDate> entryDates);

    @Query("select new com.itda.backend.repository.record.GroupStatusCount(e.childId, e.entryDate, e.status, count(e)) "
            + "from JournalEntry e join RawRecord r on r.id = e.rawRecordId "
            + "where e.status in :statuses and r.institutionId = :institutionId "
            + "and e.childId is not null and e.entryDate is not null and e.deletedAt is null "
            + "group by e.childId, e.entryDate, e.status")
    List<GroupStatusCount> countByGroupAndStatus(@Param("statuses") Collection<JournalEntryStatus> statuses,
            @Param("institutionId") String institutionId);

    @Query("select new com.itda.backend.repository.record.DateCount(e.entryDate, count(e)) "
            + "from JournalEntry e join RawRecord r on r.id = e.rawRecordId "
            + "where e.status in :statuses and r.institutionId = :institutionId and e.entryDate in :entryDates "
            + "and e.deletedAt is null "
            + "group by e.entryDate")
    List<DateCount> countByDate(@Param("statuses") Collection<JournalEntryStatus> statuses,
            @Param("institutionId") String institutionId, @Param("entryDates") Collection<LocalDate> entryDates);

    @Query("select count(e) from JournalEntry e join RawRecord r on r.id = e.rawRecordId "
            + "where e.status in :statuses and r.institutionId = :institutionId "
            + "and e.childId is null and e.deletedAt is null")
    long countChildUnknown(@Param("statuses") Collection<JournalEntryStatus> statuses,
            @Param("institutionId") String institutionId);

    @Query("select distinct new com.itda.backend.repository.record.InProgressKey(r.institutionId, e.entryDate) "
            + "from JournalEntry e join RawRecord r on r.id = e.rawRecordId "
            + "where e.status in :statuses and e.entryDate is not null and e.deletedAt is null")
    List<InProgressKey> findInProgressKeys(@Param("statuses") Collection<JournalEntryStatus> statuses);

    @Query("select count(e) from JournalEntry e join RawRecord r on r.id = e.rawRecordId "
            + "where r.institutionId = :institutionId and e.createdAt >= :since")
    long countCreatedSince(@Param("institutionId") String institutionId, @Param("since") LocalDateTime since);

    @Query("select new com.itda.backend.repository.record.ProgressEntryKey("
            + "e.id, e.childId, e.entryDate, r.institutionId) "
            + "from JournalEntry e join RawRecord r on r.id = e.rawRecordId "
            + "where e.id in :ids and e.deletedAt is null")
    List<ProgressEntryKey> findProgressKeys(@Param("ids") Collection<Long> ids);

    @Query("select new com.itda.backend.repository.record.PipelineEntry("
            + "e.id, e.childId, e.entryDate, e.status, e.failureStage) "
            + "from JournalEntry e join RawRecord r on r.id = e.rawRecordId "
            + "where r.institutionId = :institutionId and e.status in :statuses and e.deletedAt is null "
            + "order by e.entryDate, e.id")
    List<PipelineEntry> findPipelineEntries(@Param("institutionId") String institutionId,
            @Param("statuses") Collection<JournalEntryStatus> statuses);

    List<JournalEntry> findByChildIdInAndDeletedAtIsNullAndStatusNotIn(Collection<Long> childIds,
            Collection<JournalEntryStatus> statuses);
}
