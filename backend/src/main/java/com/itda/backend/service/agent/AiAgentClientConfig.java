package com.itda.backend.service.agent;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.itda.backend.global.config.HttpClientRequestFactories;

@Configuration
@EnableConfigurationProperties({AiAgentProperties.class, AgentDownProperties.class, AgentQueueProperties.class})
public class AiAgentClientConfig {

    @Bean
    public RestClient aiAgentRestClient(RestClient.Builder builder, AiAgentProperties properties) {
        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(HttpClientRequestFactories.create(properties.connectTimeout(), properties.readTimeout()))
                .build();
    }
}
