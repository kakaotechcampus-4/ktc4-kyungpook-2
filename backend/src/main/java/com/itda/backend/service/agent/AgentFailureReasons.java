package com.itda.backend.service.agent;

import com.itda.backend.domain.record.FailureReason;
import com.itda.backend.exception.agent.AiAgentRejectedException;
import com.itda.backend.exception.agent.AiAgentUnavailableException;
import com.itda.backend.exception.agent.AiAgentUnreadableResponseException;

/** 워커가 받은 예외를 시스템 오류 원인으로 바꾼다 (#180). 검증·요약 워커가 같은 기준을 쓰게 한 곳에 둔다. */
public final class AgentFailureReasons {

    private AgentFailureReasons() {
    }

    public static FailureReason of(RuntimeException e) {
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
