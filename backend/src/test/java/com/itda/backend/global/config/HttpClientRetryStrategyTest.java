package com.itda.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.hc.client5.http.HttpRequestRetryStrategy;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.junit.jupiter.api.Test;

class HttpClientRetryStrategyTest {

    private final HttpRequestRetryStrategy strategy = HttpClientRequestFactories.retryStrategy();
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
        BasicClassicHttpRequest validate = new BasicClassicHttpRequest("POST", "/validation");

        assertThat(strategy.retryRequest(validate, new NoHttpResponseException("closed"), 1, context)).isFalse();
    }
}
