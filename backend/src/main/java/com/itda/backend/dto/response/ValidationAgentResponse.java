package com.itda.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.itda.backend.domain.ValidationVerdict;

/**
 * 검증 에이전트 응답. 필드는 {@code AI/validation/schemas.py} 의 ValidationOutput 과 1:1 이다.
 *
 * <p>{@code issueTypes}/{@code evidence} 는 AI 가 보낸 JSON 조각 그대로 두고 validation_result 에 그대로 저장한다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ValidationAgentResponse(
        Long journalEntryId,
        ValidationVerdict verdict,
        JsonNode issueTypes,
        JsonNode evidence) {
}
