package com.itda.backend.service.agent;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.itda.backend.global.config.HttpClientRequestFactories;

@Configuration
@EnableConfigurationProperties({AiAgentProperties.class, AgentDownProperties.class, AgentQueueProperties.class})
public class AiAgentClientConfig {

    /**
     * 타임아웃은 여기서만 정한다. {@link AiAgentClient} 안에서 요청 팩토리를 다시 바꾸면
     * 테스트의 MockRestServiceServer 가 덮여서 실제 네트워크로 나간다.
     *
     * <p>HTTP 클라이언트의 503·429 자동 재시도는 끈다 — 다시 보내는 건 {@link AiAgentClient} 만 한다
     * ({@link HttpClientRequestFactories}, #153).
     */
    @Bean
    public RestClient aiAgentRestClient(RestClient.Builder builder, AiAgentProperties properties) {
        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(HttpClientRequestFactories.create(properties.connectTimeout(), properties.readTimeout()))
                .build();
    }
}
