package com.itda.backend.dto.response.insight;

/**
 * Gate 2 결정 결과 (api-spec O-42). Gate 1 의 {@code Gate1DecisionResponse} 와 같은 모양이다 —
 * FE 가 두 화면을 같은 틀로 만들 수 있게 맞춘다.
 *
 * @param gate2Status 결정 뒤 인사이트 상태 — approved · hold
 * @param decision    {@code human_review} 에 남긴 결정값 — APPROVED · HOLD
 */
public record Gate2DecisionResponse(String insightId, String gate2Status, String decision) {
}
