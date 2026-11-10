package com.itda.backend.repository.summary;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.summary.SummaryRunRequest;

public interface SummaryRunRequestRepository extends JpaRepository<SummaryRunRequest, Long> {

    boolean existsByChildIdAndEntryDateAndInstitutionId(Long childId, LocalDate entryDate, Long institutionId);

    @Modifying(flushAutomatically = true)
    @Query("update SummaryRunRequest r set r.requestedAt = :requestedAt "
            + "where r.childId = :childId and r.entryDate = :entryDate and r.institutionId = :institutionId "
            + "and r.requestedAt < :requestedAt")
    int advanceRequestedAt(@Param("childId") Long childId, @Param("entryDate") LocalDate entryDate,
            @Param("institutionId") Long institutionId, @Param("requestedAt") LocalDateTime requestedAt);

    @Modifying(flushAutomatically = true)
    @Query("update SummaryRunRequest r set r.handledUntil = :until "
            + "where r.childId = :childId and r.entryDate = :entryDate and r.institutionId = :institutionId "
            + "and (r.handledUntil is null or r.handledUntil < :until)")
    int advanceHandledUntil(@Param("childId") Long childId, @Param("entryDate") LocalDate entryDate,
            @Param("institutionId") Long institutionId, @Param("until") LocalDateTime until);

    @Query("select count(r) > 0 from SummaryRunRequest r "
            + "where r.childId = :childId and r.entryDate = :entryDate and r.institutionId = :institutionId "
            + "and (r.handledUntil is null or r.requestedAt > r.handledUntil)")
    boolean existsPending(@Param("childId") Long childId, @Param("entryDate") LocalDate entryDate,
            @Param("institutionId") Long institutionId);

    @Query("select r from SummaryRunRequest r where r.handledUntil is null or r.requestedAt > r.handledUntil")
    List<SummaryRunRequest> findPending();

    @Query("select r from SummaryRunRequest r "
            + "where r.institutionId = :institutionId and r.entryDate in :dates "
            + "and (r.handledUntil is null or r.requestedAt > r.handledUntil)")
    List<SummaryRunRequest> findPendingIn(@Param("institutionId") Long institutionId,
            @Param("dates") Collection<LocalDate> dates);
}
