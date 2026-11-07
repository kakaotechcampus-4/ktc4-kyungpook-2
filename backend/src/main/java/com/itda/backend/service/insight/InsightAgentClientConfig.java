package com.itda.backend.service.insight;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.itda.backend.global.config.HttpClientRequestFactories;
import com.itda.backend.service.agent.AiAgentProperties;

/**
 * 인사이트 에이전트 전용 RestClient (#386). 접속 주소·연결 대기는 공통 {@code app.ai.*} 를 쓰고, 응답 대기 시간만
 * {@code app.insight.read-timeout} 으로 바꾼다 — AI 가 요청 하나를 180초(INSIGHT_DEADLINE) 안에 끝낸다.
 *
 * <p>요약({@link com.itda.backend.service.summary.SummaryAgentClientConfig})과 같은 모양이다.
 */
@Configuration
public class InsightAgentClientConfig {

    @Bean
    public RestClient insightAgentRestClient(RestClient.Builder builder, AiAgentProperties properties,
            InsightProperties insightProperties) {
        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(HttpClientRequestFactories.create(
                        properties.connectTimeout(), insightProperties.readTimeout()))
                .build();
    }
}
