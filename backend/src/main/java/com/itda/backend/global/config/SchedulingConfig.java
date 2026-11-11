package com.itda.backend.global.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.itda.backend.service.insight.InsightProperties;
import com.itda.backend.service.notify.NotifyProperties;
import com.itda.backend.service.matching.MatchingProperties;
import com.itda.backend.service.summary.SummaryProperties;
import com.itda.backend.service.user.WithdrawalProperties;
import com.itda.backend.service.validation.ValidationProperties;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({MatchingProperties.class, ValidationProperties.class, SummaryProperties.class,
        InsightProperties.class, NotifyProperties.class, WithdrawalProperties.class})
public class SchedulingConfig {
}
