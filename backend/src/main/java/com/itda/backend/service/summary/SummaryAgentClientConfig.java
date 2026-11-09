package com.itda.backend.service.summary;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.itda.backend.global.config.HttpClientRequestFactories;
import com.itda.backend.service.agent.AiAgentProperties;

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
