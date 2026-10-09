package com.itda.backend.repository;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.SummaryResult;

public interface SummaryResultRepository extends JpaRepository<SummaryResult, Long> {

    /** 그 묶음의 가장 최근 판. 승인 전이면 덮어쓰고, 승인됐으면 다음 판 번호의 기준이 된다. */
    Optional<SummaryResult> findFirstByChildIdAndEntryDateAndInstitutionIdOrderByRevisionDesc(
            Long childId, LocalDate entryDate, Long institutionId);
}
