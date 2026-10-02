package com.itda.backend.global.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.itda.backend.service.matching.MatchingProperties;

/** {@code @Scheduled} 작업(매칭 워커)을 켠다. 워커 자체는 {@code app.matching.worker.enabled} 로 켜고 끈다. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(MatchingProperties.class)
public class SchedulingConfig {
}
