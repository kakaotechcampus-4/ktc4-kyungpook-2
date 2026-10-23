package com.itda.backend.exception.agent;

/**
 * AI 에이전트가 요청을 거절했다(4xx, FastAPI 422 포함). BE 가 계약과 다른 요청을 보냈다는 뜻이라 다시 보내도 같다.
 * 워커는 이 일지를 시스템 오류(REQUEST_REJECTED)로 멈추고, BE 를 고친 뒤 되돌린다 (#180).
 */
public class AiAgentRejectedException extends AiAgentException {

    public AiAgentRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
