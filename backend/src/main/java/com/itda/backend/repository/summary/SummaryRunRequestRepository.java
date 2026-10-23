package com.itda.backend.repository.summary;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.summary.SummaryRunRequest;

import jakarta.persistence.LockModeType;

public interface SummaryRunRequestRepository extends JpaRepository<SummaryRunRequest, Long> {

    /**
     * 요청을 바꾸기 전에 그 묶음의 행을 잠근다. 교사가 다시 누르는 것과 워커가 처리 표시를 하는 것이 겹쳐도 한쪽이
     * 다른 쪽이 바꾼 칸을 옛 값으로 덮지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SummaryRunRequest r "
            + "where r.childId = :childId and r.entryDate = :entryDate and r.institutionId = :institutionId")
    Optional<SummaryRunRequest> findGroupForUpdate(@Param("childId") Long childId,
            @Param("entryDate") LocalDate entryDate, @Param("institutionId") Long institutionId);

    @Query("select count(r) > 0 from SummaryRunRequest r "
            + "where r.childId = :childId and r.entryDate = :entryDate and r.institutionId = :institutionId "
            + "and (r.handledUntil is null or r.requestedAt > r.handledUntil)")
    boolean existsPending(@Param("childId") Long childId, @Param("entryDate") LocalDate entryDate,
            @Param("institutionId") Long institutionId);

    @Query("select r from SummaryRunRequest r where r.handledUntil is null or r.requestedAt > r.handledUntil")
    List<SummaryRunRequest> findPending();
}
