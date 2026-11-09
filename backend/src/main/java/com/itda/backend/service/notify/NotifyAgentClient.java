package com.itda.backend.service.notify;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.itda.backend.dto.request.notify.NotifyAgentRequest;
import com.itda.backend.dto.response.notify.NotifyAgentResponse;
import com.itda.backend.exception.agent.AiAgentUnavailableException;
import com.itda.backend.service.agent.AiAgentClient;
import com.itda.backend.service.agent.AiAgentProperties;

@Component
public class NotifyAgentClient {

    private final AiAgentClient agentClient;

    @Autowired
    public NotifyAgentClient(@Qualifier("notifyAgentRestClient") RestClient restClient, ObjectMapper objectMapper,
            AiAgentProperties properties, NotifyProperties notifyProperties) {
        this(new AiAgentClient(restClient, objectMapper, properties, notifyProperties.timeoutRetries()));
    }

    NotifyAgentClient(AiAgentClient agentClient) {
        this.agentClient = agentClient;
    }

    public NotifyAgentResponse send(NotifyAgentRequest request) {
        String context = "childId=" + request.childId()
                + " period=" + request.periodFrom() + ".." + request.periodTo()
                + " notificationId=" + request.notificationId();
        return agentClient.parse(agentClient.post("/notify", request, context), NotifyAgentResponse.class);
    }

    public String failureOf(RuntimeException e) {
        if (e instanceof AiAgentUnavailableException unavailable) {
            String reason = agentClient.detailOf(unavailable, "reason");
            if (reason != null) {
                String failure = agentClient.detailOf(unavailable, "failure");
                return failure == null ? reason : reason + ":" + failure;
            }
            return "agent_unreachable";
        }
        return "agent_error";
    }

    public boolean isAvailable() {
        return agentClient.isAvailable();
    }
}
