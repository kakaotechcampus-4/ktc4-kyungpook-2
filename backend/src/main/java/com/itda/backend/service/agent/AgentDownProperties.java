package com.itda.backend.service.agent;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai.agent-down")
public record AgentDownProperties(int maxReleases, Duration pause) {
}
