package com.itda.backend.repository.summary;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.summary.SummaryRunRequest;

public interface SummaryRunRequestRepository extends JpaRepository<SummaryRunRequest, Long> {

    Optional<SummaryRunRequest> findByChildIdAndEntryDateAndInstitutionId(
            Long childId, LocalDate entryDate, Long institutionId);

    boolean existsByChildIdAndEntryDateAndInstitutionId(Long childId, LocalDate entryDate, Long institutionId);

    /** {@code requestedAt} 이 {@code until} 보다 늦은 요청은 남긴다. */
    @Modifying(flushAutomatically = true)
    @Query("delete from SummaryRunRequest r "
            + "where r.childId = :childId and r.entryDate = :entryDate and r.institutionId = :institutionId "
            + "and r.requestedAt <= :until")
    int deleteRequestedUntil(@Param("childId") Long childId, @Param("entryDate") LocalDate entryDate,
            @Param("institutionId") Long institutionId, @Param("until") LocalDateTime until);

    @Modifying(flushAutomatically = true)
    @Query("delete from SummaryRunRequest r "
            + "where r.childId = :childId and r.entryDate = :entryDate and r.institutionId = :institutionId")
    int deleteGroup(@Param("childId") Long childId, @Param("entryDate") LocalDate entryDate,
            @Param("institutionId") Long institutionId);
}
