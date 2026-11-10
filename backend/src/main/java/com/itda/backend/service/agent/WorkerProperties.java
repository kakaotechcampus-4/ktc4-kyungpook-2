package com.itda.backend.service.agent;

public record WorkerProperties(boolean enabled, long delayMs, int batchSize) {
}
