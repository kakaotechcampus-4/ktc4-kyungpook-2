package com.itda.backend.service.validation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.itda.backend.service.agent.WorkerProperties;

/** {@code app.validation.*} 설정. AI 서버 접속 설정은 {@code app.ai.*}({@link com.itda.backend.service.agent.AiAgentProperties}). */
@ConfigurationProperties(prefix = "app.validation")
public record ValidationProperties(WorkerProperties worker) {
}
