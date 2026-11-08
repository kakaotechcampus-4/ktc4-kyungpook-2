package com.itda.backend.service.summary;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.itda.backend.service.agent.WorkerProperties;

/**
 * {@code app.summary.*} 설정. AI 서버 접속 설정은 {@code app.ai.*}({@link com.itda.backend.service.agent.AiAgentProperties}).
 *
 * @param readTimeout 요약 에이전트 응답 대기 시간. AI 가 LLM 을 90초까지 기다리므로 그보다 길어야 한다
 * @param autoRun     자동 요약 (#369). 켜져 있으면 같은 기관·날짜에 처리 중인 일지가 없을 때 [요약] 요청 없이 묶음을 집는다.
 *                    끄면 교사가 [요약]을 누른 묶음만 요약한다 (#181) — 장애 때 되돌리는 스위치다
 */
@ConfigurationProperties(prefix = "app.summary")
public record SummaryProperties(WorkerProperties worker, Duration readTimeout, boolean autoRun) {
}
