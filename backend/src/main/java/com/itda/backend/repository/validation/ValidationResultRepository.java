package com.itda.backend.repository.validation;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.validation.ValidationResult;
import com.itda.backend.domain.validation.ValidationVerdict;

public interface ValidationResultRepository extends JpaRepository<ValidationResult, Long> {

    // 수정 요청 큐(O-24). 같은 일지를 다시 검증하면 행이 쌓이므로(§8.1) 최신 것이 먼저 오게 정렬한다.
    // 정렬만으로는 중복이 걸러지지 않는다 — 일지당 한 건으로 줄이는 것, 소속 기관 필터,
    // "지금도 막힌 상태인지" 확인은 모두 ValidationQueueService 가 한다.
    List<ValidationResult> findByVerdictOrderByIdDesc(ValidationVerdict verdict);

    // Gate 1 목록. 일지당 최신 것을 고르도록 최신순이다 — 고르는 것은 SummaryGate1QueryService 가 한다.
    List<ValidationResult> findByJournalEntryIdInOrderByIdDesc(Collection<Long> journalEntryIds);

    // 수정 요청 큐 처리(O-25). 화면이 보던 판정이 그 일지의 최신 판정인지 본다 — 고쳐서 다시 막혔으면 옛 카드다 (#357).
    Optional<ValidationResult> findFirstByJournalEntryIdOrderByIdDesc(Long journalEntryId);

    /** 그 기관 일지에 {@code since} 뒤로 남은 검증 결과 중 주어진 판정 수 (#370). 다시 검사하면 행이 하나 더 생겨 따로 센다. */
    @Query("select count(v) from ValidationResult v join JournalEntry e on e.id = v.journalEntryId "
            + "join RawRecord r on r.id = e.rawRecordId "
            + "where r.institutionId = :institutionId and v.verdict in :verdicts and v.createdAt >= :since")
    long countByVerdictSince(@Param("institutionId") String institutionId,
            @Param("verdicts") Collection<ValidationVerdict> verdicts, @Param("since") LocalDateTime since);
}
