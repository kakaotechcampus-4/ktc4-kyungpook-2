package com.itda.backend.service.user;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.withdrawal")
public record WithdrawalProperties(boolean enabled, Duration waitForCompletion, String lock, Kakao kakao, Worker worker) {

    public record Kakao(String baseUrl, String adminKey, String appId, Duration connectTimeout, Duration readTimeout) {
    }

    public record Worker(boolean enabled, long delayMs) {
    }
}
