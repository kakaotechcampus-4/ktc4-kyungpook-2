package com.itda.backend.service.agent;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.itda.backend.global.config.HttpClientRequestFactories;

@Configuration
@EnableConfigurationProperties(AiProgressProperties.class)
public class AiProgressClientConfig {

    @Bean
    public RestClient aiProgressRestClient(RestClient.Builder builder, AiAgentProperties aiProperties,
            AiProgressProperties properties) {
        return builder.clone()
                .baseUrl(aiProperties.baseUrl())
                .requestFactory(HttpClientRequestFactories.create(properties.timeout(), properties.timeout()))
                .build();
    }
}
