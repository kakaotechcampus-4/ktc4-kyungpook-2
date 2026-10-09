package com.itda.backend.global.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.itda.backend.service.matching.MatchingProperties;
import com.itda.backend.service.summary.SummaryProperties;
import com.itda.backend.service.validation.ValidationProperties;

/**
 * {@code @Scheduled} 작업(에이전트 워커)을 켠다. 워커마다 {@code app.<단계>.worker.enabled} 로 켜고 끈다.
 * 스레드 수는 application.yml 의 {@code spring.task.scheduling.pool.size} 로 정한다 (워커당 하나).
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({MatchingProperties.class, ValidationProperties.class, SummaryProperties.class})
public class SchedulingConfig {
}
