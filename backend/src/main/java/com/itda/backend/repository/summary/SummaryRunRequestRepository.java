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

    /**
     * 다시 눌렀다. 더 늦은 시각일 때만 그 칸 하나를 바꾼다.
     *
     * <p>엔티티를 읽어 고치지 않고 조건부 UPDATE 한 번으로 바꾼다. 같은 트랜잭션에 미리 읽어 둔 엔티티가 있으면
     * 잠가도 옛 값이 돌아오고, 저장할 때 다른 칸(처리 시각)까지 옛 값으로 덮는다. 시각이 앞으로만 가는 규칙도 DB 가
     * 한 문장으로 지킨다.
     */
    @Modifying(flushAutomatically = true)
    @Query("update SummaryRunRequest r set r.requestedAt = :requestedAt "
            + "where r.childId = :childId and r.entryDate = :entryDate and r.institutionId = :institutionId "
            + "and r.requestedAt < :requestedAt")
    int advanceRequestedAt(@Param("childId") Long childId, @Param("entryDate") LocalDate entryDate,
            @Param("institutionId") Long institutionId, @Param("requestedAt") LocalDateTime requestedAt);

    /** {@code until} 까지 누른 요청을 처리했다. 처리 시각도 앞으로만 간다. 바꾸는 방식은 {@link #advanceRequestedAt} 과 같다. */
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
