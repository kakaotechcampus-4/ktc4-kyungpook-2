package com.itda.backend.service.agent;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.itda.backend.service.summary.SummaryProperties;

@Configuration
@EnableConfigurationProperties(AiAgentProperties.class)
public class AiAgentClientConfig {

    /**
     * 타임아웃은 여기서만 정한다. {@link AiAgentClient} 안에서 요청 팩토리를 다시 바꾸면
     * 테스트의 MockRestServiceServer 가 덮여서 실제 네트워크로 나간다.
     */
    @Bean
    public RestClient aiAgentRestClient(RestClient.Builder builder, AiAgentProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withTimeouts(properties.connectTimeout(), properties.readTimeout());
        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }

    /** 요약 전용. 요약은 LLM 을 더 오래 기다리므로 응답 대기 시간만 {@code app.summary.read-timeout} 으로 바꾼다. */
    @Bean
    public RestClient summaryAgentRestClient(RestClient.Builder builder, AiAgentProperties properties,
            SummaryProperties summaryProperties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withTimeouts(properties.connectTimeout(), summaryProperties.readTimeout());
        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }
}
