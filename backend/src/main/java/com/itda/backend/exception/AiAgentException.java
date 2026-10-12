package com.itda.backend.exception;

/** AI 에이전트 호출이 실패했거나 응답을 읽을 수 없다. 워커가 받아서 그 일지를 FAILED 로 기록한다. */
public class AiAgentException extends RuntimeException {

    public AiAgentException(String message) {
        super(message);
    }

    public AiAgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
