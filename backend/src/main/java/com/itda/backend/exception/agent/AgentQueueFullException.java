package com.itda.backend.exception.agent;

/** 기관의 AI 처리 줄이 꽉 차서 새 일지를 받지 않았다 (#424). {@code 429 AGENT_QUEUE_FULL} 로 응답한다. */
public class AgentQueueFullException extends RuntimeException {

    public AgentQueueFullException(Long organizationId, long awaiting, int incoming, int max) {
        super("agent queue full organizationId=" + organizationId + " awaiting=" + awaiting
                + " incoming=" + incoming + " max=" + max);
    }
}
