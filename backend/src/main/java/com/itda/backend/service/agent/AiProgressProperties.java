package com.itda.backend.service.agent;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.ai-progress.*} — 대시보드 진행 현황에 AI 진행 단계를 띄우는 설정 (#453).
 *
 * @param enabled  켜기/끄기. 코드 기본값은 꺼짐이고 배포 서버는 compose 의 {@code AI_PROGRESS_ENABLED} 로 켠다
 * @param interval AI {@code GET /progress} 를 묻는 간격
 * @param timeout  그 요청의 연결·응답 대기 시간. 넘으면 이번 차례는 실패로 본다
 */
@ConfigurationProperties(prefix = "app.ai-progress")
public record AiProgressProperties(boolean enabled, Duration interval, Duration timeout) {
}
