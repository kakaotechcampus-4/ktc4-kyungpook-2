package com.itda.backend.exception.agent;

import org.springframework.http.HttpStatus;

import com.itda.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** docs/api/api-conventions.md — AI 처리 줄 오류 코드 (#424). 업로드와 선생님 처리 네 경로가 함께 쓴다. */
@Getter
@RequiredArgsConstructor
public enum AgentQueueErrorCode implements ErrorCode {

    QUEUE_FULL(HttpStatus.TOO_MANY_REQUESTS, "AGENT_QUEUE_FULL", "처리를 기다리는 기록이 많습니다. 잠시 뒤 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
