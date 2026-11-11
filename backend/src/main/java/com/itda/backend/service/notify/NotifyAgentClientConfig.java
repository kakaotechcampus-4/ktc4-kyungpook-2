package com.itda.backend.service.notify;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.itda.backend.global.config.HttpClientRequestFactories;
import com.itda.backend.service.agent.AiAgentProperties;

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
