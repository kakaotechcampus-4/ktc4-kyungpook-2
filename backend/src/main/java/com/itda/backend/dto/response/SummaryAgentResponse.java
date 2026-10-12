package com.itda.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 요약 에이전트 응답. 필드는 {@code AI/summary/schemas.py} 의 SummaryOutput 과 1:1 이다.
 *
 * <p>{@code claims}/{@code coveredEntryIds}/{@code uncoveredEntryIds} 는 AI 가 보낸 JSON 조각 그대로 두고
 * summary_result 에 그대로 저장한다.
 *
 * <p>200 이어도 {@code content} 가 빌 수 있다 — 근거를 원문에서 못 찾은 문장은 AI 가 버리므로 전부 버려지면 빈다.
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
        Boolean llmCalled) {
}
