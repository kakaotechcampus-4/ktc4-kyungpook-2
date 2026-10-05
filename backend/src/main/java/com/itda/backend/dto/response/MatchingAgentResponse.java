package com.itda.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.itda.backend.domain.MatchingStatus;
import com.itda.backend.domain.MultiReason;

/**
 * 매칭 에이전트 응답. 필드는 {@code AI/matching/schemas.py} 의 MatchingOutput 과 1:1 이다.
 *
 * <p>{@code candidates}/{@code evidence}/{@code mentionedChildIds} 는 AI 가 보낸 JSON 조각 그대로 둔다.
 * matching_result 에 이 형식({@code child_id} 등 snake_case) 그대로 저장해야 확인 필요 큐가 읽을 수 있다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record MatchingAgentResponse(
        Long journalEntryId,
        MatchingStatus status,
        Long matchedChildId,
        Double confidence,
        Boolean hintMismatch,
        JsonNode evidence,
        JsonNode mentionedChildIds,
        MultiReason multiReason,
        JsonNode candidates,
        Boolean llmCalled) {
}
