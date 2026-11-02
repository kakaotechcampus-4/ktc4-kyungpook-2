package com.itda.backend.exception.agent;

/**
 * AI 에이전트 호출이 실패했다. 워커가 받아서 매칭은 FAILED(확인 필요 큐)로, 검증·요약은 하위 타입에 따라 원인을 골라
 * 시스템 오류로 기록한다 (#180, {@code AgentFailureReasons}).
 */
public class AiAgentException extends RuntimeException {

    public AiAgentException(String message) {
        super(message);
    }

    public AiAgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
