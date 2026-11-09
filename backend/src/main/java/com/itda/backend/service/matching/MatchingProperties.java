package com.itda.backend.service.matching;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.itda.backend.service.agent.WorkerProperties;

@ConfigurationProperties(prefix = "app.matching")
public record MatchingProperties(WorkerProperties worker) {
}
