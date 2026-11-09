package com.itda.backend.service.approval;

import java.time.LocalDate;

import com.itda.backend.domain.summary.SummaryStatus;

/**
 * 대시보드 보드에 올리는 Gate 1 요약 한 편. {@code Gate1SummaryResponse} 에서 보드가 쓰는 칸만 남겼다.
 *
 * @param status     GATE1_PENDING(검토 대기) · HOLD(보류함)
 * @param processing 같은 묶음에 아직 요약에 반영되지 않은 일지가 있다 — {@code Gate1SummaryResponse#processing} 과 같은 판정
 * @param noContent  일지에 쓸 내용이 없다는 요약
 */
public record Gate1BoardItem(Long id, Long childId, String childName, LocalDate entryDate, SummaryStatus status,
        boolean processing, boolean noContent) {
}
