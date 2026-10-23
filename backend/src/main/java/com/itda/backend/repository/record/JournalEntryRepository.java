package com.itda.backend.repository.record;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {

    /**
     * 그 파일들에서 나온 일지. 처리 현황(O-26)이 파일마다 따로 묻지 않고 한 번에 읽는 데 쓴다 —
     * 파일 수만큼 쿼리가 늘면 목록이 길어질수록 느려진다.
     */
    List<JournalEntry> findByRawRecordIdInAndDeletedAtIsNullOrderByIdAsc(Collection<Long> rawRecordIds);


    Optional<JournalEntry> findByIdAndDeletedAtIsNull(Long id);

    /**
     * 매칭 워커가 처리할 일지. 원본 파일이 없는 직접 입력은 매칭 단계를 건너뛰므로 뺀다 (DB 스키마 §6.2).
     * 오래된 것부터 처리하도록 id 순으로 가져온다.
     */
    @Query("select e from JournalEntry e "
            + "where e.status = :status and e.rawRecordId is not null and e.deletedAt is null "
            + "order by e.id")
    List<JournalEntry> findMatchingTargets(@Param("status") JournalEntryStatus status, Limit limit);

    /**
     * 검증 워커가 처리할 일지. 아동이 비어 있으면 AI 가 판정 대상을 몰라 REVIEW(대상불명확)로 흘려보내므로 뺀다.
     * 오래된 것부터 처리하도록 id 순으로 가져온다.
     */
    @Query("select e from JournalEntry e "
            + "where e.status = :status and e.childId is not null and e.deletedAt is null "
            + "order by e.id")
    List<JournalEntry> findValidationTargets(@Param("status") JournalEntryStatus status, Limit limit);

    List<JournalEntry> findByStatusAndDeletedAtIsNull(JournalEntryStatus status);

    /**
     * 요약 묶음 후보. 묶음 키(아동·날짜·기관)가 다 있는 일지만 가져온다 — 원본 파일이 없는 직접 입력은 기관을 몰라 뺀다.
     * 묶음 안에서 일지 순서가 유지되도록 id 순으로 가져온다.
     */
    @Query("select new com.itda.backend.repository.record.SummaryCandidate("
            + "e.id, e.childId, e.entryDate, r.institutionId, e.createdAt) "
            + "from JournalEntry e join RawRecord r on r.id = e.rawRecordId "
            + "where e.status in :statuses and e.childId is not null and e.entryDate is not null "
            + "and e.deletedAt is null "
            + "order by e.id")
    List<SummaryCandidate> findSummaryCandidates(@Param("statuses") Collection<JournalEntryStatus> statuses);

    /** 한 묶음(아동·날짜·기관)에서 주어진 상태인 일지 id. 묶음 전체를 훑지 않고 필요한 묶음만 본다. */
    @Query("select e.id from JournalEntry e join RawRecord r on r.id = e.rawRecordId "
            + "where e.status = :status and e.childId = :childId and e.entryDate = :entryDate "
            + "and r.institutionId = :institutionId and e.deletedAt is null "
            + "order by e.id")
    List<Long> findGroupEntryIds(@Param("status") JournalEntryStatus status, @Param("childId") Long childId,
            @Param("entryDate") LocalDate entryDate, @Param("institutionId") String institutionId);

    /** 한 요약(판)에 든 일지 중 주어진 상태인 것의 id. */
    @Query("select e.id from JournalEntry e "
            + "where e.summaryId = :summaryId and e.status = :status and e.deletedAt is null "
            + "order by e.id")
    List<Long> findIdsBySummaryIdAndStatus(@Param("summaryId") Long summaryId,
            @Param("status") JournalEntryStatus status);

    /** 한 요약(판)에 든 삭제되지 않은 일지. 요약에 안 들어간 일지(uncovered)도 같은 요약을 가리킨다. */
    List<JournalEntry> findBySummaryIdAndDeletedAtIsNull(Long summaryId);

    /** Gate 1 목록용. 여러 요약에 든 삭제되지 않은 일지를 한 번에. */
    @Query("select e from JournalEntry e where e.summaryId in :summaryIds and e.deletedAt is null order by e.id")
    List<JournalEntry> findLiveBySummaryIds(@Param("summaryIds") Collection<Long> summaryIds);

    /** 그 기관·날짜에 주어진 상태(매칭·검증 진행 중)의 일지가 남아 있는지. */
    @Query("select count(e) > 0 from JournalEntry e join RawRecord r on r.id = e.rawRecordId "
            + "where e.status in :statuses and r.institutionId = :institutionId and e.entryDate = :entryDate "
            + "and e.deletedAt is null")
    boolean existsInProgress(@Param("statuses") Collection<JournalEntryStatus> statuses,
            @Param("institutionId") String institutionId, @Param("entryDate") LocalDate entryDate);

    /**
     * Gate 1 목록용. 한 기관의 주어진 날짜들 중 주어진 상태인 일지가 있는 묶음(아동·날짜). 요약마다 따로 묻지 않고
     * 목록 전체를 한 번에 본다.
     */
    @Query("select distinct new com.itda.backend.repository.record.GroupKey(e.childId, e.entryDate) "
            + "from JournalEntry e join RawRecord r on r.id = e.rawRecordId "
            + "where e.status in :statuses and r.institutionId = :institutionId and e.entryDate in :entryDates "
            + "and e.childId is not null and e.deletedAt is null")
    List<GroupKey> findGroupKeys(@Param("statuses") Collection<JournalEntryStatus> statuses,
            @Param("institutionId") String institutionId, @Param("entryDates") Collection<LocalDate> entryDates);

    /** Gate 1 목록용. 한 기관의 주어진 날짜들 중 주어진 상태(매칭·검증 진행 중)의 일지가 남아 있는 날짜. */
    @Query("select distinct e.entryDate from JournalEntry e join RawRecord r on r.id = e.rawRecordId "
            + "where e.status in :statuses and r.institutionId = :institutionId and e.entryDate in :entryDates "
            + "and e.deletedAt is null")
    List<LocalDate> findInProgressDates(@Param("statuses") Collection<JournalEntryStatus> statuses,
            @Param("institutionId") String institutionId, @Param("entryDates") Collection<LocalDate> entryDates);

    /** 주어진 상태(매칭·검증 진행 중)의 일지가 남아 있는 기관·날짜. */
    @Query("select distinct new com.itda.backend.repository.record.InProgressKey(r.institutionId, e.entryDate) "
            + "from JournalEntry e join RawRecord r on r.id = e.rawRecordId "
            + "where e.status in :statuses and e.entryDate is not null and e.deletedAt is null")
    List<InProgressKey> findInProgressKeys(@Param("statuses") Collection<JournalEntryStatus> statuses);
}
