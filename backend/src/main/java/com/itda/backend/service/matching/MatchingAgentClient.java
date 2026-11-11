package com.itda.backend.service.matching;

import org.springframework.stereotype.Component;

import com.itda.backend.dto.request.matching.MatchingAgentRequest;
import com.itda.backend.dto.response.matching.MatchingAgentResponse;
import com.itda.backend.service.agent.AiAgentClient;

import lombok.RequiredArgsConstructor;

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
