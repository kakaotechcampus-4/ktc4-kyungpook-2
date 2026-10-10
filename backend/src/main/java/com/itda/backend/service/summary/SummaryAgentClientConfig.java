package com.itda.backend.service.summary;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.itda.backend.service.agent.AiAgentProperties;

/**
 * 요약 에이전트 전용 RestClient. 접속 주소·연결 대기는 공통 {@code app.ai.*} 를 쓰고, 응답 대기 시간만
 * {@code app.summary.read-timeout} 으로 바꾼다 — 요약은 LLM 을 90초까지 기다린다.
 *
 * <p>공통 설정({@link com.itda.backend.service.agent.AiAgentClientConfig})이 요약을 알지 않도록 여기 둔다.
 */
@Configuration
public class SummaryAgentClientConfig {

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
