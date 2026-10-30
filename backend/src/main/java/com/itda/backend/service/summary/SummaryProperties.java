package com.itda.backend.service.summary;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.itda.backend.service.agent.WorkerProperties;

/**
 * {@code app.summary.*} 설정. AI 서버 접속 설정은 {@code app.ai.*}({@link com.itda.backend.service.agent.AiAgentProperties}).
 *
 * <p>언제 요약할지는 설정이 아니라 교사의 [요약] 요청이 정한다 (#181).
 *
 * @param readTimeout 요약 에이전트 응답 대기 시간. AI 가 LLM 을 90초까지 기다리므로 그보다 길어야 한다
 */
@ConfigurationProperties(prefix = "app.summary")
public record SummaryProperties(WorkerProperties worker, Duration readTimeout) {
}
