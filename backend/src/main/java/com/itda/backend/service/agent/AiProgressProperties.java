package com.itda.backend.service.agent;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai-progress")
public record AiProgressProperties(boolean enabled, Duration interval, Duration timeout) {

    public AiProgressProperties {
        interval = interval == null ? Duration.ofSeconds(1) : interval;
        timeout = timeout == null ? Duration.ofMillis(500) : timeout;
    }
}
