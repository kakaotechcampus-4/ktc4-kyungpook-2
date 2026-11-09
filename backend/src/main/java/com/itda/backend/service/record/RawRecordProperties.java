package com.itda.backend.service.record;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.raw-record")
public record RawRecordProperties(Limits limits, Upload upload) {

    public record Limits(int maxEntriesPerFile) {
    }

    public record Upload(int maxConcurrent, int maxWaiting, Duration maxWait) {
    }
}
