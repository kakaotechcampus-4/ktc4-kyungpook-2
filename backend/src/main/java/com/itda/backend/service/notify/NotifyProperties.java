package com.itda.backend.service.notify;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.itda.backend.service.agent.WorkerProperties;

@ConfigurationProperties(prefix = "app.notify")
public record NotifyProperties(WorkerProperties worker, List<Duration> failureBackoffs,
        Duration readTimeout, int timeoutRetries) {
}
