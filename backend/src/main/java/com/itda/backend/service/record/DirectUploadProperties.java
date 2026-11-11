package com.itda.backend.service.record;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "app.raw-record.direct-upload")
public record DirectUploadProperties(Duration urlTtl, Duration abandonAfter, DataSize maxFileSize) {
}
