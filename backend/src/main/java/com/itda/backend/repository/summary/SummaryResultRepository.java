package com.itda.backend.repository.summary;

import java.time.LocalDate;
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

    /** 그 묶음의 가장 최근 판. 승인 전이면 덮어쓰고, 승인됐으면 다음 판 번호의 기준이 된다. */
    Optional<SummaryResult> findFirstByChildIdAndEntryDateAndInstitutionIdOrderByRevisionDesc(
            Long childId, LocalDate entryDate, Long institutionId);

    /** Gate 1 목록. 오래된 날짜부터 처리하도록 날짜순이다. */
    List<SummaryResult> findByInstitutionIdAndStatusOrderByEntryDateAscIdAsc(Long institutionId,
            SummaryStatus status);

    /** Gate 1 결정용. 두 결정이 동시에 와도 한쪽만 대기 상태에서 바꾸도록 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SummaryResult s where s.id = :id")
    Optional<SummaryResult> findByIdForUpdate(@Param("id") Long id);
}
