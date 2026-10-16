package com.itda.backend.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.hc.client5.http.HttpRequestRetryStrategy;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.junit.jupiter.api.Test;

/**
 * HTTP 클라이언트가 스스로 하는 재시도 (#153).
 *
 * <p>응답 코드를 보고 다시 보내는 것(503·429)은 {@link AiAgentClient} 가 하므로 끈다. 켜 두면 3번이 6번이 된다.
 * 다만 서버가 막 닫은 keep-alive 연결을 다시 써서 GET 이 응답 없이 끊기는 경우는 계속 다시 보낸다 —
 * {@code /health} 는 앱 쪽 재시도가 없어서, 이것까지 끄면 AI 가 멀쩡한데 "응답 없음" 으로 차례를 건너뛴다.
 */
class AiAgentRetryStrategyTest {

    private final HttpRequestRetryStrategy strategy = AiAgentClientConfig.retryStrategy();
    private final HttpClientContext context = HttpClientContext.create();

    @Test
    void 응답_코드를_보고_다시_보내지_않는다() {
        assertThat(strategy.retryRequest(new BasicHttpResponse(503), 1, context)).isFalse();
        assertThat(strategy.retryRequest(new BasicHttpResponse(429), 1, context)).isFalse();
    }

    @Test
    void 끊긴_연결로_GET_이_실패하면_한_번_다시_보낸다() {
        BasicClassicHttpRequest health = new BasicClassicHttpRequest("GET", "/health");

        assertThat(strategy.retryRequest(health, new NoHttpResponseException("closed"), 1, context)).isTrue();
        assertThat(strategy.retryRequest(health, new NoHttpResponseException("closed"), 2, context)).isFalse();
    }

    @Test
    void POST_는_연결이_끊겨도_다시_보내지_않는다() {
        // AI 가 받아서 LLM 을 부르는 중이었을 수 있다. 다시 보낼지는 AiAgentClient 가 정한다.
        BasicClassicHttpRequest validate = new BasicClassicHttpRequest("POST", "/validation");

        assertThat(strategy.retryRequest(validate, new NoHttpResponseException("closed"), 1, context)).isFalse();
    }
}
