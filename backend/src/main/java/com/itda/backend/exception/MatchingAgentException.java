package com.itda.backend.exception;

/** 매칭 에이전트 호출이 재시도까지 실패했거나 응답을 읽을 수 없다. 워커가 받아서 FAILED 로 기록한다. */
public class MatchingAgentException extends RuntimeException {

    public MatchingAgentException(String message) {
        super(message);
    }

    public MatchingAgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
