package com.itda.backend.service.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.agent-queue")
public record AgentQueueProperties(int maxAwaitingEntries) {
}
