package com.itda.backend.global.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 현재 시각을 빈으로 둔다. 요약 마감·디바운스처럼 시각에 따라 갈리는 로직을 테스트에서 고정된 시계로 재기 위해서다. */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
