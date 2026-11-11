package com.itda.backend.service.summary;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.dto.request.summary.SummaryAgentRequest;
import com.itda.backend.dto.response.summary.SummaryAgentResponse;
import com.itda.backend.exception.agent.AiAgentUnavailableException;
import com.itda.backend.exception.summary.SummaryNoGroundedClaimsException;
import com.itda.backend.service.agent.AiAgentClient;
import com.itda.backend.service.agent.AiAgentProperties;

@Component
public class SummaryAgentClient {

    private static final String NO_GROUNDED_CLAIMS = "no_grounded_claims";

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
        Long firstEntryId = request.sources().isEmpty() ? null : request.sources().get(0).journalEntryId();
        String body;
        try {
            body = agentClient.post("/summary", request, firstEntryId);
        } catch (AiAgentUnavailableException e) {
            if (NO_GROUNDED_CLAIMS.equals(agentClient.detailOf(e, "reason"))) {
                throw new SummaryNoGroundedClaimsException(
                        "summary has no grounded claims after retries journalEntryId=" + firstEntryId, e);
            }
            throw e;
        }
        return new SummaryAgentReply(agentClient.parse(body, SummaryAgentResponse.class), body);
    }

    public boolean isAvailable() {
        return agentClient.isAvailable();
    }
}
