package com.itda.backend.service.notify;

import com.itda.backend.dto.request.notify.NotifyAgentRequest;

/**
 * 한 번 보낼 거리 (#419).
 *
 * @param request      AI 에 보낼 요청. <b>보호자 이메일이 없다</b> — 주소는 AI 쪽 MCP 서버가 찾는다
 * @param insightCount 이번 메일에 담은 인사이트 수
 * @param lastInsightId 이번에 본 {@code insight_result} 중 가장 큰 id. 다음 차례가 이 값과 견줘
 *                      재료가 늘었는지 본다
 */
public record PreparedNotify(NotifyAgentRequest request, int insightCount, Long lastInsightId) {
}
