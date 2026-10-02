package com.itda.backend.service.agent;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.ai.*} 설정. 매칭·검증·요약 에이전트가 같은 AI 서버에 있어서 접속 설정을 함께 쓴다.
 *
 * @param baseUrl        AI 서버 주소. 로컬 {@code http://localhost:8000}, compose {@code http://ai:8000}
 * @param connectTimeout 연결 대기 시간
 * @param readTimeout    응답 대기 시간. AI 가 LLM 응답을 60초까지 기다리므로 그보다 길어야 한다
 * @param retryBackoffs  재시도 전에 쉬는 시간. 개수가 곧 재시도 횟수다
 */
@ConfigurationProperties(prefix = "app.ai")
public record AiAgentProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        List<Duration> retryBackoffs) {
}
