package com.itda.backend.exception.summary;

import com.itda.backend.exception.agent.AiAgentException;
import com.itda.backend.exception.agent.AiAgentUnavailableException;

public class SummaryNoGroundedClaimsException extends AiAgentException {

    public SummaryNoGroundedClaimsException(String message, Throwable cause) {
        super(message, cause);
    }
}
