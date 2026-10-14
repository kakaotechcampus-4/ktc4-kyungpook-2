package com.itda.backend.service.agent;

import java.time.Duration;

import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(AiAgentProperties.class)
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
     * <p>Apache HttpClient 5 는 기본으로 503·429 를 한 번 더 보낸다. 재시도는 {@link AiAgentClient} 한 곳에서만
     * 하므로 여기서 끈다. 켜 두면 설정한 3번이 실제로는 6번이 된다 (#153).
     */
    public static ClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withTimeouts(connectTimeout, readTimeout);
        return ClientHttpRequestFactoryBuilder.httpComponents()
                .withHttpClientCustomizer(HttpClientBuilder::disableAutomaticRetries)
                .build(settings);
    }
}
