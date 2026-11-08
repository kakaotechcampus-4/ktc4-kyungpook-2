package com.itda.backend.repository.matching;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.matching.MatchingResult;
import com.itda.backend.domain.matching.MatchingStatus;

public interface MatchingResultRepository extends JpaRepository<MatchingResult, Long> {

    List<MatchingResult> findByStatusNot(MatchingStatus status);

    /** 검증 결과에 어떤 매칭 결과를 입력으로 썼는지 남길 때 쓴다. 지금은 일지당 한 행이지만 재처리로 쌓이면 최신 것을 쓴다. */
    Optional<MatchingResult> findFirstByJournalEntryIdOrderByIdDesc(Long journalEntryId);

    /** 요약 묶음의 일지들의 매칭 결과를 한 번에 가져온다. 재처리로 쌓인 옛 행도 함께 오므로 최신 것은 호출하는 쪽이 고른다. */
    List<MatchingResult> findByJournalEntryIdIn(Collection<Long> journalEntryIds);

    /**
     * 그 기관 일지 중 {@code since} 뒤로 AI 가 그대로 확정한 매칭 수 (#370). 선생님이 큐에서 처리한 행도 {@code AUTO} 가
     * 되지만 {@code reviewerId} 가 남아 뺀다.
     */
    @Query("select count(m) from MatchingResult m join JournalEntry e on e.id = m.journalEntryId "
            + "join RawRecord r on r.id = e.rawRecordId "
            + "where r.institutionId = :institutionId and m.status = :status and m.reviewerId is null "
            + "and m.createdAt >= :since")
    long countConfirmedByAgentSince(@Param("institutionId") String institutionId,
            @Param("status") MatchingStatus status, @Param("since") LocalDateTime since);
}
