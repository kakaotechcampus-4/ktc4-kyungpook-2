package com.itda.backend.repository.summary;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.summary.SummaryResult;

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

    /**
     * 그 기간에 요약이 하나라도 있는 아이들. 인사이트 워커가 누구를 볼지 고르는 데 쓴다.
     *
     * <p>승인 여부는 보지 않는다 — 승인·동의·근거를 따지는 자리는 {@code InsightService.prepare} 하나로
     * 두는 게 낫다. 여기서는 <b>볼 필요조차 없는 아이</b>를 빼는 것이 목적이다.
     */
    @Query("select distinct s.childId from SummaryResult s where s.entryDate between :from and :to")
    List<Long> findChildIdsWithSummaryBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
