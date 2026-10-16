package com.itda.backend.service.insight;

import com.itda.backend.dto.request.insight.InsightAgentRequest;

/**
 * 인사이트에 보낼 재료와, 그 재료를 <b>어디까지 보고 모았는지</b>.
 *
 * <p>둘을 함께 돌려주는 이유 — 눈금은 재료를 모은 그 순간의 것이어야 한다. 저장할 때 다시 재면,
 * 모으고 저장하는 사이에 들어온 승인까지 "봤다" 고 적혀 그 기록이 영영 빠진다 (§9.5).
 *
 * @param lastContextId 재료가 된 {@code child_context} 중 가장 큰 id
 */
public record PreparedInsight(InsightAgentRequest request, Long lastContextId) {
}
