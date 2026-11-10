package com.itda.backend.repository.validation;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.validation.ValidationResult;
import com.itda.backend.domain.validation.ValidationVerdict;

public interface ValidationResultRepository extends JpaRepository<ValidationResult, Long> {

    @Modifying(flushAutomatically = true)
    @Query("delete from ValidationResult v where v.journalEntryId in :journalEntryIds")
    int deleteByJournalEntryIds(@Param("journalEntryIds") Collection<Long> journalEntryIds);

    List<ValidationResult> findByVerdictOrderByIdDesc(ValidationVerdict verdict);

    List<ValidationResult> findByJournalEntryIdInOrderByIdDesc(Collection<Long> journalEntryIds);

    Optional<ValidationResult> findFirstByJournalEntryIdOrderByIdDesc(Long journalEntryId);

    @Query("select count(v) from ValidationResult v join JournalEntry e on e.id = v.journalEntryId "
            + "join RawRecord r on r.id = e.rawRecordId "
            + "where r.institutionId = :institutionId and v.verdict in :verdicts and v.createdAt >= :since")
    long countByVerdictSince(@Param("institutionId") String institutionId,
            @Param("verdicts") Collection<ValidationVerdict> verdicts, @Param("since") LocalDateTime since);
}
