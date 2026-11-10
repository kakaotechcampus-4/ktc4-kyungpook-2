package com.itda.backend.exception.agent;

public class AiAgentUnreadableResponseException extends AiAgentException {

    public AiAgentUnreadableResponseException(String message) {
        super(message);
    }

    public AiAgentUnreadableResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
