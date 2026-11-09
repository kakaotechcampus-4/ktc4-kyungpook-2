package com.itda.backend.repository.matching;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.matching.MatchingResult;
import com.itda.backend.domain.matching.MatchingStatus;

public interface MatchingResultRepository extends JpaRepository<MatchingResult, Long> {

    @Modifying(flushAutomatically = true)
    @Query("delete from MatchingResult m where m.journalEntryId in :journalEntryIds")
    int deleteByJournalEntryIds(@Param("journalEntryIds") Collection<Long> journalEntryIds);

    List<MatchingResult> findByStatusNot(MatchingStatus status);

    Optional<MatchingResult> findFirstByJournalEntryIdOrderByIdDesc(Long journalEntryId);

    List<MatchingResult> findByJournalEntryIdIn(Collection<Long> journalEntryIds);

    @Query("select count(m) from MatchingResult m join JournalEntry e on e.id = m.journalEntryId "
            + "join RawRecord r on r.id = e.rawRecordId "
            + "where r.institutionId = :institutionId and m.status = :status and m.reviewerId is null "
            + "and m.createdAt >= :since")
    long countConfirmedByAgentSince(@Param("institutionId") String institutionId,
            @Param("status") MatchingStatus status, @Param("since") LocalDateTime since);
}
