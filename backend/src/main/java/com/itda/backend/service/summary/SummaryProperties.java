package com.itda.backend.service.summary;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.itda.backend.service.agent.WorkerProperties;

@ConfigurationProperties(prefix = "app.summary")
public record SummaryProperties(WorkerProperties worker, Duration readTimeout, boolean autoRun) {
}
