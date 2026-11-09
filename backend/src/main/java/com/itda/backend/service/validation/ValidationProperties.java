package com.itda.backend.service.validation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.itda.backend.service.agent.WorkerProperties;

@ConfigurationProperties(prefix = "app.validation")
public record ValidationProperties(WorkerProperties worker) {
}
