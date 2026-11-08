package com.itda.backend.exception.agent;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.itda.backend.global.exception.ErrorResponse;

import lombok.extern.slf4j.Slf4j;

// AI 처리 줄 예외만 처리한다. 업로드와 선생님 처리 네 경로(아이 고르기 · 본문 고치기 · 다시 요약 · 내용 없음 되돌리기)가
// 같은 코드로 응답하도록 한 곳에 둔다. @Order 는 다른 도메인 핸들러와 같은 이유 — 전역 catch-all 보다 먼저 잡는다.
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AgentQueueExceptionHandler {

    @ExceptionHandler(AgentQueueFullException.class)
    public ResponseEntity<ErrorResponse> handleQueueFull(AgentQueueFullException e) {
        log.info("rejected: {}", e.getMessage());
        return ResponseEntity.status(AgentQueueErrorCode.QUEUE_FULL.getStatus())
                .body(ErrorResponse.of(AgentQueueErrorCode.QUEUE_FULL));
    }
}
