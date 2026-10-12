package com.itda.backend.service.validation;

import org.springframework.stereotype.Component;

import com.itda.backend.dto.request.ValidationAgentRequest;
import com.itda.backend.dto.response.ValidationAgentResponse;
import com.itda.backend.service.agent.AiAgentClient;

import lombok.RequiredArgsConstructor;

/**
 * 검증 에이전트({@code AI POST /validation}) 호출. 재시도·상태 확인은 {@link AiAgentClient} 가 한다.
 *
 * <p>AI 는 LLM 을 못 써서 판정을 못 하면 503 을 준다(#98). 5xx 라서 재시도하고, 그래도 실패하면
 * "AI 를 쓸 수 없음"이 되어 워커가 이번 차례를 멈춘다. 정규식으로 개인정보가 잡힌 경우는 LLM 과 상관없이
 * 200 + BLOCK 이다.
 */
@Component
@RequiredArgsConstructor
public class ValidationAgentClient {

    private final AiAgentClient agentClient;

    public ValidationAgentReply validate(ValidationAgentRequest request) {
        String body = agentClient.post("/validation", request, request.journalEntryId());
        return new ValidationAgentReply(agentClient.parse(body, ValidationAgentResponse.class), body);
    }

    public boolean isAvailable() {
        return agentClient.isAvailable();
    }
}
