package com.itda.backend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.MatchingResult;
import com.itda.backend.domain.MatchingStatus;

public interface MatchingResultRepository extends JpaRepository<MatchingResult, Long> {

    List<MatchingResult> findByStatusNot(MatchingStatus status);

    /** 검증 결과에 어떤 매칭 결과를 입력으로 썼는지 남길 때 쓴다. 지금은 일지당 한 행이지만 재처리로 쌓이면 최신 것을 쓴다. */
    Optional<MatchingResult> findFirstByJournalEntryIdOrderByIdDesc(Long journalEntryId);
}
