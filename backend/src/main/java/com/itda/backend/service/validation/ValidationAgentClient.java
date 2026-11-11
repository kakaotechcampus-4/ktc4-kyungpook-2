package com.itda.backend.service.validation;

import org.springframework.stereotype.Component;

import com.itda.backend.dto.request.validation.ValidationAgentRequest;
import com.itda.backend.dto.response.validation.ValidationAgentResponse;
import com.itda.backend.service.agent.AiAgentClient;

import lombok.RequiredArgsConstructor;

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
