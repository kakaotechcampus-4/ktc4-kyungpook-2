package com.itda.backend.service.matching;

import org.springframework.stereotype.Component;

import com.itda.backend.dto.request.MatchingAgentRequest;
import com.itda.backend.dto.response.MatchingAgentResponse;
import com.itda.backend.service.agent.AiAgentClient;

import lombok.RequiredArgsConstructor;

/** 매칭 에이전트({@code AI POST /matching}) 호출. 재시도·상태 확인은 {@link AiAgentClient} 가 한다. */
@Component
@RequiredArgsConstructor
public class MatchingAgentClient {

    private final AiAgentClient agentClient;

    public MatchingAgentReply match(MatchingAgentRequest request) {
        String body = agentClient.post("/matching", request, request.journalEntryId());
        return new MatchingAgentReply(agentClient.parse(body, MatchingAgentResponse.class), body);
    }

    public boolean isAvailable() {
        return agentClient.isAvailable();
    }
}
