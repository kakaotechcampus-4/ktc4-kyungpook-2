package com.itda.backend.service.summary;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.dto.request.SummaryAgentRequest;
import com.itda.backend.dto.response.SummaryAgentResponse;
import com.itda.backend.service.agent.AiAgentClient;
import com.itda.backend.service.agent.AiAgentProperties;

/**
 * 요약 에이전트({@code AI POST /summary}) 호출. 재시도·상태 확인은 {@link AiAgentClient} 가 한다.
 *
 * <p>요약은 LLM 을 90초까지 기다려서(AI/summary/config.py LUNA_TIMEOUT) 매칭·검증용 응답 대기 시간(70초)으로는
 * 느린 정상 응답을 먼저 끊고 다시 부르게 된다. 그래서 응답 대기 시간이 긴 RestClient 를 따로 쓴다.
 *
 * <p>AI 는 LLM 을 못 쓰면 503 을 준다. 5xx 라서 재시도하고, 그래도 실패하면 워커가 이번 차례를 멈춘다.
 */
@Component
public class SummaryAgentClient {

    private final AiAgentClient agentClient;

    @Autowired
    public SummaryAgentClient(@Qualifier("summaryAgentRestClient") RestClient restClient, ObjectMapper objectMapper,
            AiAgentProperties properties) {
        this(new AiAgentClient(restClient, objectMapper, properties));
    }

    SummaryAgentClient(AiAgentClient agentClient) {
        this.agentClient = agentClient;
    }

    public SummaryAgentReply summarize(SummaryAgentRequest request) {
        // 일지 한 건이 아니라 묶음이라 로그에는 첫 일지 id 를 남긴다.
        Long firstEntryId = request.sources().isEmpty() ? null : request.sources().get(0).journalEntryId();
        String body = agentClient.post("/summary", request, firstEntryId);
        return new SummaryAgentReply(agentClient.parse(body, SummaryAgentResponse.class), body);
    }

    public boolean isAvailable() {
        return agentClient.isAvailable();
    }
}
