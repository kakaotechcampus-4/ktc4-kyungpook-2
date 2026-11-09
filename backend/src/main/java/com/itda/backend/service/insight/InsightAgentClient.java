package com.itda.backend.service.insight;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.itda.backend.dto.request.insight.InsightAgentRequest;
import com.itda.backend.dto.response.insight.InsightAgentResponse;
import com.itda.backend.exception.agent.AiAgentRejectedException;
import com.itda.backend.exception.agent.AiAgentUnavailableException;
import com.itda.backend.exception.agent.AiAgentUnreadableResponseException;
import com.itda.backend.service.agent.AiAgentClient;
import com.itda.backend.service.agent.AiAgentProperties;

@Component
public class InsightAgentClient {

    private final AiAgentClient agentClient;

    @Autowired
    public InsightAgentClient(@Qualifier("insightAgentRestClient") RestClient restClient, ObjectMapper objectMapper,
            AiAgentProperties properties, InsightProperties insightProperties) {
        this(new AiAgentClient(restClient, objectMapper, properties, insightProperties.timeoutRetries()));
    }

    InsightAgentClient(AiAgentClient agentClient) {
        this.agentClient = agentClient;
    }

    public InsightAgentReply analyze(InsightAgentRequest request) {
        String context = "childId=" + request.childId()
                + " period=" + request.periodFrom() + ".." + request.periodTo();
        String body = agentClient.post("/insight", request, context);
        return new InsightAgentReply(agentClient.parse(body, InsightAgentResponse.class), body);
    }

    public String failureOf(RuntimeException e) {
        if (e instanceof AiAgentUnavailableException unavailable) {
            String reason = agentClient.detailOf(unavailable, "reason");
            if (reason == null) {
                return "agent_unreachable";
            }
            String failure = agentClient.detailOf(unavailable, "failure");
            return failure == null ? reason : reason + ":" + failure;
        }
        if (e instanceof AiAgentRejectedException) {
            return "agent_rejected";
        }
        if (e instanceof AiAgentUnreadableResponseException) {
            return "unreadable_response";
        }
        return "error";
    }

    public boolean isAvailable() {
        return agentClient.isAvailable();
    }
}
