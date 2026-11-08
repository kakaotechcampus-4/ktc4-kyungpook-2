package com.itda.backend.service.notify;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.itda.backend.global.config.HttpClientRequestFactories;
import com.itda.backend.service.agent.AiAgentProperties;

/**
 * 알림 에이전트 전용 RestClient (#419). 접속 주소·연결 대기는 공통 {@code app.ai.*} 를 쓰고, 응답 대기만
 * {@code app.notify.read-timeout} 으로 바꾼다 — 모델 호출 한 번 + SMTP 왕복이라 인사이트보다 짧다.
 *
 * <p>{@link com.itda.backend.service.insight.InsightAgentClientConfig} 와 같은 모양이다.
 */
@Configuration
public class NotifyAgentClientConfig {

    @Bean
    public RestClient notifyAgentRestClient(RestClient.Builder builder, AiAgentProperties properties,
            NotifyProperties notifyProperties) {
        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(HttpClientRequestFactories.create(
                        properties.connectTimeout(), notifyProperties.readTimeout()))
                .build();
    }
}
