package com.itda.backend.service.matching;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.matching.*} 설정.
 *
 * @param aiBaseUrl      매칭 에이전트 주소. 로컬 {@code http://localhost:8000}, compose {@code http://ai:8000}
 * @param connectTimeout 연결 대기 시간
 * @param readTimeout    응답 대기 시간. AI 가 LLM 응답을 60초까지 기다리므로 그보다 길어야 한다
 * @param retryBackoffs  재시도 전에 쉬는 시간. 개수가 곧 재시도 횟수다
 */
@ConfigurationProperties(prefix = "app.matching")
public record MatchingProperties(
        String aiBaseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        List<Duration> retryBackoffs,
        Worker worker) {

    /**
     * @param enabled   꺼 두면 워커 빈 자체가 만들어지지 않는다 (테스트 프로필)
     * @param delayMs   한 번 처리를 마친 뒤 다음 처리까지 쉬는 시간
     * @param batchSize 한 번에 집어 가는 일지 수
     */
    public record Worker(boolean enabled, long delayMs, int batchSize) {
    }
}
