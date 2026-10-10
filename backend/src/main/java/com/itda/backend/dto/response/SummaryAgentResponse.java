package com.itda.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 요약 에이전트 응답. 필드는 {@code AI/summary/schemas.py} 의 SummaryOutput 과 1:1 이다.
 *
 * <p>{@code claims}/{@code coveredEntryIds}/{@code uncoveredEntryIds} 는 AI 가 보낸 JSON 조각 그대로 두고
 * summary_result 에 그대로 저장한다. {@code needsReview}/{@code reviewReasons} 도 같다 — 요약 본문에 다른 아이 이름이
 * 남았으면 {@code true}/{@code ["다른아동이름"]} 이다.
 *
 * <p>근거를 원문에서 못 찾은 문장은 AI 가 버린다. 전부 버려지면 200 이 아니라 503({@code no_grounded_claims})으로 온다
 * ({@link com.itda.backend.service.summary.SummaryAgentClient}).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SummaryAgentResponse(
        Long childId,
        String entryDate,
        Long institutionId,
        String content,
        JsonNode claims,
        JsonNode coveredEntryIds,
        JsonNode uncoveredEntryIds,
        Boolean needsReview,
        JsonNode reviewReasons,
        Boolean llmCalled) {
}
