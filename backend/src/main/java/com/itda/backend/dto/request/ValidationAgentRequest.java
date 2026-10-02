package com.itda.backend.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 검증 에이전트 {@code POST /validation} 요청. 필드는 {@code AI/validation/schemas.py} 의 ValidationInput 과 1:1 이다.
 *
 * <p>{@code subjectName} 이 비면 AI 가 판정 대상을 몰라 REVIEW(대상불명확)로 보낸다 — 매칭으로 확정된 아동 이름을 넣는다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ValidationAgentRequest(
        Long journalEntryId,
        String content,
        Long subjectChildId,
        String subjectName) {
}
