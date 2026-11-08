package com.itda.backend.repository.insight;

import java.time.LocalDate;

/**
 * Gate 2 를 통과한 인사이트의 (아이, 기간). 알림 워커가 <b>어느 기간을 보낼지</b> 고른다 (#419).
 *
 * <p>{@link com.itda.backend.repository.summary.ApprovedEntryDay} 와 같은 자리다.
 */
public record ApprovedInsightPeriod(Long childId, LocalDate periodFrom, LocalDate periodTo) {
}
