package com.itda.backend.service.agent;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.itda.backend.global.config.HttpClientRequestFactories;

/**
 * AI {@code GET /progress} 전용 RestClient (#453). 주소는 공통 {@code app.ai.base-url} 을 쓰고, 대기 시간은
 * {@code app.ai-progress.timeout} 으로 짧게 둔다 — 1초마다 묻는데 느린 답을 기다릴 이유가 없다.
 *
 * <p>일지 처리용 {@code aiAgentRestClient} 와 따로 둔다. 그쪽 설정을 바꾸지 않고, 진행 표시가 어떻게 되든 본 호출에
 * 닿지 않게 하려는 것이다. 기존 클라이언트는 모두 {@code @Qualifier} 로 주입받아 빈이 하나 늘어도 섞이지 않는다.
 */
@Configuration
@EnableConfigurationProperties(AiProgressProperties.class)
public class AiProgressClientConfig {

    @Bean
    public RestClient aiProgressRestClient(RestClient.Builder builder, AiAgentProperties aiProperties,
            AiProgressProperties properties) {
        // 받은 Builder 를 고치지 않고 복제해서 쓴다. 테스트가 가짜 AI 서버를 붙인 Builder 를 빈으로 바꿔 끼우면 그 하나를
        // 모든 AI 클라이언트가 나눠 받는데, 여기서 요청 팩토리를 덮으면 가짜 서버가 풀려 실제 네트워크로 나간다
        // (InsightPipelineIntegrationTest 가 CI 에서 실제로 깨졌다).
        return builder.clone()
                .baseUrl(aiProperties.baseUrl())
                .requestFactory(HttpClientRequestFactories.create(properties.timeout(), properties.timeout()))
                .build();
    }
}
