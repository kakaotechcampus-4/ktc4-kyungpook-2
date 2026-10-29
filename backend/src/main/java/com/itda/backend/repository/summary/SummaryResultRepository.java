package com.itda.backend.repository.summary;

import java.time.LocalDate;
import java.util.Collection;
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

    /**
     * 한 아이의 관찰 날짜 구간에 걸친 요약 전부. 양 끝을 포함한다.
     *
     * <p>인사이트가 기간치 재료를 모으는 첫 단계다. 승인 여부는 여기서 보지 않는다 —
     * {@code child_context} 에 행이 있는지로 따로 거른다.
     *
     * <p><b>기간을 쿼리에서 거르는 이유</b> — 승인본부터 집으면 그 아이의 승인 이력 전체를 올려 놓고
     * 자바에서 한 주치만 남기게 된다. {@code child_context} 는 지우지 않고 쌓이기만 해서 그 수가 계속 는다.
     */
    List<SummaryResult> findByChildIdAndEntryDateBetween(Long childId, LocalDate from, LocalDate to);

    /** 그 묶음에 이 판보다 새 판이 있는지. 워커는 최신 판만 덮어쓰므로, 새 판이 있으면 이 판은 더 바뀌지 않는다. */
    @Query("select count(s) > 0 from SummaryResult s "
            + "where s.childId = :childId and s.entryDate = :entryDate and s.institutionId = :institutionId "
            + "and s.revision > :revision")
    boolean existsNewerRevision(@Param("childId") Long childId, @Param("entryDate") LocalDate entryDate,
            @Param("institutionId") Long institutionId, @Param("revision") Integer revision);

    /** Gate 1 목록용. 한 기관의 주어진 아동·날짜 묶음마다 가장 큰 판 번호. 요약마다 따로 묻지 않고 한 번에 본다. */
    @Query("select new com.itda.backend.repository.summary.LatestRevision(s.childId, s.entryDate, max(s.revision)) "
            + "from SummaryResult s "
            + "where s.institutionId = :institutionId and s.childId in :childIds and s.entryDate in :entryDates "
            + "group by s.childId, s.entryDate")
    List<LatestRevision> findLatestRevisions(@Param("institutionId") Long institutionId,
            @Param("childIds") Collection<Long> childIds, @Param("entryDates") Collection<LocalDate> entryDates);

    /** Gate 1 목록. 오래된 날짜부터 처리하도록 날짜순이다. */
    @Query("select s from SummaryResult s where s.institutionId = :institutionId and s.status = :status "
            + "order by s.entryDate, s.id")
    List<SummaryResult> findGate1List(@Param("institutionId") Long institutionId,
            @Param("status") SummaryStatus status);

    /**
     * 요약 워커가 묶음을 집을 때 그 묶음의 판을 최신 판부터 잠근다. Gate 1 결정·보류 해제도 요약 행을 잠그므로, 결정이
     * 커밋되기 전에 옛 상태를 보고 승인된 일지를 다시 묶거나, 집는 사이에 판이 바뀌는 일이 없다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SummaryResult s "
            + "where s.childId = :childId and s.entryDate = :entryDate and s.institutionId = :institutionId "
            + "order by s.revision desc")
    List<SummaryResult> findGroupForUpdate(@Param("childId") Long childId, @Param("entryDate") LocalDate entryDate,
            @Param("institutionId") Long institutionId);

    /**
     * Gate 1 결정용. 두 결정이 동시에 와도 한쪽만 대기 상태에서 바꾸도록 행을 잠근다. 기관 조건을 함께 걸어 다른 기관
     * 요약 행은 잠그지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SummaryResult s where s.id = :id and s.institutionId = :institutionId")
    Optional<SummaryResult> findByIdAndInstitutionIdForUpdate(@Param("id") Long id,
            @Param("institutionId") Long institutionId);
}
