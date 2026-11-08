package com.itda.backend.service.summary;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.itda.backend.global.config.HttpClientRequestFactories;
import com.itda.backend.service.agent.AiAgentProperties;

/**
 * 요약 에이전트 전용 RestClient. 접속 주소·연결 대기는 공통 {@code app.ai.*} 를 쓰고, 응답 대기 시간만
 * {@code app.summary.read-timeout} 으로 바꾼다 — 요약은 LLM 을 90초까지 기다린다.
 *
 * <p>공통 설정({@link com.itda.backend.service.agent.AiAgentClientConfig})이 요약을 알지 않도록 여기 둔다.
 * 요청 팩토리(503·429 자동 재시도 끔)는 AI 호출과 같은 {@link HttpClientRequestFactories} 를 쓴다.
 */
@Configuration
public class SummaryAgentClientConfig {

    @Bean
    public RestClient summaryAgentRestClient(RestClient.Builder builder, AiAgentProperties properties,
            SummaryProperties summaryProperties) {
        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(HttpClientRequestFactories.create(
                        properties.connectTimeout(), summaryProperties.readTimeout()))
                .build();
    }
}
