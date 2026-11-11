package com.itda.backend.service.agent;

import com.itda.backend.domain.record.FailureReason;
import com.itda.backend.exception.agent.AgentContentTooLongException;
import com.itda.backend.exception.agent.AiAgentRejectedException;
import com.itda.backend.exception.agent.AiAgentUnavailableException;
import com.itda.backend.exception.agent.AiAgentUnreadableResponseException;

public final class AgentFailureReasons {

    private AgentFailureReasons() {
    }

    public static FailureReason of(RuntimeException e) {
        if (e instanceof AgentContentTooLongException) {
            return FailureReason.CONTENT_TOO_LONG;
        }
        if (e instanceof AiAgentRejectedException) {
            return FailureReason.REQUEST_REJECTED;
        }
        if (e instanceof AiAgentUnreadableResponseException) {
            return FailureReason.RESPONSE_UNREADABLE;
        }
        if (e instanceof AiAgentUnavailableException) {
            return FailureReason.AGENT_UNAVAILABLE;
        }
        return FailureReason.INTERNAL_ERROR;
    }
}
