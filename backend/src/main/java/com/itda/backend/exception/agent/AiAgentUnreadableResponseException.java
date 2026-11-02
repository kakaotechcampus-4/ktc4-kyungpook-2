package com.itda.backend.exception.agent;

/**
 * AI 에이전트가 200 을 줬는데 응답을 읽을 수 없다(본문 없음, JSON 아님, 계약에 없는 값·필수 값 빠짐). AI 가 계약과 다른
 * 응답을 보냈다는 뜻이라 워커는 이 일지를 시스템 오류(RESPONSE_UNREADABLE)로 멈추고, 고친 뒤 되돌린다 (#180).
 */
public class AiAgentUnreadableResponseException extends AiAgentException {

    public AiAgentUnreadableResponseException(String message) {
        super(message);
    }

    public AiAgentUnreadableResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
