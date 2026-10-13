package com.itda.backend.exception;

/**
 * 요약 에이전트가 재시도까지 근거가 남은 문장을 하나도 내지 못했다 (AI 503 {@code detail.reason=no_grounded_claims}).
 *
 * <p>상태 코드는 LLM 장애와 같은 503 이지만 AI 가 죽은 것이 아니라 그 묶음에서 근거를 못 댄 것이다.
 * {@link AiAgentUnavailableException} 으로 보면 워커가 차례를 통째로 멈춰서, 늘 근거가 0개인 묶음 하나가 뒤의 묶음을
 * 모두 막는다. 그래서 다른 예외로 두고 워커는 그 묶음만 실패로 남긴다 (#148, #149).
 */
public class SummaryNoGroundedClaimsException extends AiAgentException {

    public SummaryNoGroundedClaimsException(String message, Throwable cause) {
        super(message, cause);
    }
}
