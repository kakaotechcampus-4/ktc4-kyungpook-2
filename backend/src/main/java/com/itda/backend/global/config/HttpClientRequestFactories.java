package com.itda.backend.global.config;

import java.time.Duration;

import org.apache.hc.client5.http.HttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.client.ClientHttpRequestFactory;

/**
 * 외부 서버(AI 서버·카카오)를 부르는 요청 팩토리. 타임아웃만 호출하는 쪽이 정한다.
 *
 * <p>Apache HttpClient 5 는 기본으로 503·429 를 한 번 더 보낸다. 그래서 여기서 끈다.
 * <ul>
 *   <li>AI 서버: 응답을 보고 다시 보내는 일은 {@code AiAgentClient} 한 곳에서만 한다. 켜 두면 설정한 3번이 실제로는 6번이 된다 (#153).</li>
 *   <li>카카오: 토큰 교환 요청의 인가 코드는 한 번만 쓸 수 있어, 다시 보내면 {@code invalid_grant} 가 된다.
 *       {@code Retry-After} 만큼 요청 스레드가 잠들기도 한다 (#193).</li>
 * </ul>
 * 연결이 끊긴 GET 을 한 번 다시 보내는 것만 남긴다 — {@link #retryStrategy()}.
 */
public final class HttpClientRequestFactories {

    private HttpClientRequestFactories() {
    }

    public static ClientHttpRequestFactory create(Duration connectTimeout, Duration readTimeout) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withTimeouts(connectTimeout, readTimeout);
        return ClientHttpRequestFactoryBuilder.httpComponents()
                .withHttpClientCustomizer(client -> client.setRetryStrategy(retryStrategy()))
                .build(settings);
    }

    /**
     * 응답 코드(503·429)를 보고 다시 보내는 것만 끄고, 연결이 응답 없이 끊긴 GET 을 한 번 다시 보내는 기본 동작은 둔다.
     *
     * <p>{@code disableAutomaticRetries()} 로 통째로 끄면 서버가 막 닫은 keep-alive 연결을 다시 썼을 때
     * {@code /health} 가 실패한다 — 앱 쪽에는 {@code /health} 재시도가 없어서 AI 가 멀쩡한데 차례를 건너뛴다.
     * POST 는 기본 정책에서도 연결 오류로 다시 보내지 않으므로 호출 수는 늘지 않는다.
     */
    static HttpRequestRetryStrategy retryStrategy() {
        return new DefaultHttpRequestRetryStrategy() {
            @Override
            public boolean retryRequest(HttpResponse response, int execCount, HttpContext context) {
                return false;
            }
        };
    }
}
