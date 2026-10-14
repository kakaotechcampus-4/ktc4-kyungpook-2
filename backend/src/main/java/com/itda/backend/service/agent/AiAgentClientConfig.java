package com.itda.backend.service.agent;

import java.time.Duration;

import org.apache.hc.client5.http.HttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({AiAgentProperties.class, AgentDownProperties.class})
public class AiAgentClientConfig {

    /**
     * 타임아웃은 여기서만 정한다. {@link AiAgentClient} 안에서 요청 팩토리를 다시 바꾸면
     * 테스트의 MockRestServiceServer 가 덮여서 실제 네트워크로 나간다.
     */
    @Bean
    public RestClient aiAgentRestClient(RestClient.Builder builder, AiAgentProperties properties) {
        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory(properties.connectTimeout(), properties.readTimeout()))
                .build();
    }

    /**
     * AI 서버를 부르는 요청 팩토리. 요약처럼 응답 대기 시간만 다른 클라이언트도 이걸 쓴다.
     *
     * <p>Apache HttpClient 5 는 기본으로 503·429 를 한 번 더 보낸다. 응답을 보고 다시 보내는 것은
     * {@link AiAgentClient} 한 곳에서만 하므로 여기서 끈다. 켜 두면 설정한 3번이 실제로는 6번이 된다 (#153).
     * 연결이 끊긴 GET 을 한 번 다시 보내는 것만 남긴다 — {@link #retryStrategy()}.
     */
    public static ClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
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
