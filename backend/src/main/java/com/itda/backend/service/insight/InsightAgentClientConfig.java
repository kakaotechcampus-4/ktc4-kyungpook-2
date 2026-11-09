package com.itda.backend.service.insight;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.itda.backend.global.config.HttpClientRequestFactories;
import com.itda.backend.service.agent.AiAgentProperties;

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
