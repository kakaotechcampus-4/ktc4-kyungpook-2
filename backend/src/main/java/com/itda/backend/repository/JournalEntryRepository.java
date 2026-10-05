package com.itda.backend.repository;

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
}
