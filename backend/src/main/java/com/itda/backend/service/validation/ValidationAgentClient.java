package com.itda.backend.service.validation;

import org.springframework.stereotype.Component;

import com.itda.backend.domain.validation.ValidationVerdict;
import com.itda.backend.dto.request.validation.ValidationAgentRequest;
import com.itda.backend.dto.response.validation.ValidationAgentResponse;
import com.itda.backend.exception.agent.AiAgentUnreadableResponseException;
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
        ValidationAgentResponse response = agentClient.parse(body, ValidationAgentResponse.class);
        // FAILED 는 BE 가 호출 실패를 남기려고 둔 값이라 AI 가 보낼 수 없다. 판정이 없는 응답과 함께 계약 위반으로 본다.
        if (response.verdict() == null || response.verdict() == ValidationVerdict.FAILED) {
            throw new AiAgentUnreadableResponseException(
                    "validation verdict missing or not in contract journalEntryId=" + request.journalEntryId());
        }
        return new ValidationAgentReply(response, body);
    }

    public boolean isAvailable() {
        return agentClient.isAvailable();
    }
}
