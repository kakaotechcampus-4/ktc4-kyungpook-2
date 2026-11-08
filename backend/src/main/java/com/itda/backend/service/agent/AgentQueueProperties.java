package com.itda.backend.service.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.agent-queue.*} 설정 (#424).
 *
 * @param maxAwaitingEntries 한 기관이 AI 처리 줄(매칭·검증·요약 대기와 처리 중)에 세워 둘 수 있는 일지 수. 업로드와 선생님 처리로
 *                           새로 들어오는 순간에만 지킨다 — 요약 워커가 승인 대기 일지를 함께 다시 담으면 잠깐 넘을 수 있다
 *                           ({@link AgentQueueGuard})
 */
@ConfigurationProperties(prefix = "app.agent-queue")
public record AgentQueueProperties(int maxAwaitingEntries) {
}
