package com.itda.backend.exception.agent;

import com.itda.backend.global.exception.BusinessException;

public class AgentQueueFullException extends BusinessException {

    public AgentQueueFullException(Long organizationId, long awaiting, int incoming, int max) {
        super(AgentQueueErrorCode.QUEUE_FULL,
                "agent queue full organizationId=" + organizationId + " awaiting=" + awaiting
                + " incoming=" + incoming + " max=" + max);
    }
}
