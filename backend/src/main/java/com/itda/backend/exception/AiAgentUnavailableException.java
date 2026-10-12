package com.itda.backend.exception;

/**
 * AI 에이전트가 재시도까지 응답하지 못했다(연결 실패·타임아웃·5xx). 요청 내용이 아니라 AI 쪽 상태 문제라서,
 * 워커는 이 건만 실패로 남기고 같이 집어 온 나머지 일지는 되돌려 놓은 뒤 이번 차례를 멈춘다.
 */
public class AiAgentUnavailableException extends AiAgentException {

    public AiAgentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
