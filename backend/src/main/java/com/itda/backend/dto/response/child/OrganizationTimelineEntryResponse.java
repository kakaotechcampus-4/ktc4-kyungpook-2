package com.itda.backend.dto.response.child;

import java.time.LocalDate;

/**
 * 기관 아동 상세의 기록 타임라인 한 건 (api-spec O-14) — 우리 기관이 Gate 1 에서 승인한 요약 하나 (#292).
 *
 * @param id          승인본({@code child_context}) id
 * @param date        관찰 날짜
 * @param content     담당자가 승인한 최종본. AI 원문({@code summary_result.content})이 아니다 — 고쳐서 승인하면 최종본은
 *                    승인본에만 있다
 * @param edited      Gate 1 에서 고쳐서 승인했는지
 * @param sourceCount 그 요약에 묶인 원본 일지 수
 */
public record OrganizationTimelineEntryResponse(
        String id,
        LocalDate date,
        String content,
        boolean edited,
        int sourceCount) {
}
