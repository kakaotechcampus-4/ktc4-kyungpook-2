package com.itda.backend.service.agent;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai")
public record AiAgentProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        List<Duration> retryBackoffs) {
}
