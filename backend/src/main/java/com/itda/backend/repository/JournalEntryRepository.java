package com.itda.backend.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.JournalEntry;
import com.itda.backend.domain.JournalEntryStatus;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {

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
    @Query("select new com.itda.backend.repository.SummaryCandidate("
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

    /** 그 기관·날짜에 주어진 상태(매칭·검증 진행 중)의 일지가 남아 있는지. */
    @Query("select count(e) > 0 from JournalEntry e join RawRecord r on r.id = e.rawRecordId "
            + "where e.status in :statuses and r.institutionId = :institutionId and e.entryDate = :entryDate "
            + "and e.deletedAt is null")
    boolean existsInProgress(@Param("statuses") Collection<JournalEntryStatus> statuses,
            @Param("institutionId") String institutionId, @Param("entryDate") LocalDate entryDate);

    /** 주어진 상태(매칭·검증 진행 중)의 일지가 남아 있는 기관·날짜. */
    @Query("select distinct new com.itda.backend.repository.InProgressKey(r.institutionId, e.entryDate) "
            + "from JournalEntry e join RawRecord r on r.id = e.rawRecordId "
            + "where e.status in :statuses and e.entryDate is not null and e.deletedAt is null")
    List<InProgressKey> findInProgressKeys(@Param("statuses") Collection<JournalEntryStatus> statuses);
}
