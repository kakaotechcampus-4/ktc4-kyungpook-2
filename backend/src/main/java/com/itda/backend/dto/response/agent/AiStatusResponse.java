package com.itda.backend.dto.response.agent;

import java.time.OffsetDateTime;

/**
 * AI 상태 (#370). 기관 화면이 AI 장애 배너를 띄울지 정한다.
 *
 * @param available AI 가 일을 받을 수 있는지
 * @param since     멈춘 것으로 보기 시작한 시각(이번 장애의 첫 실패). 사용 가능하면 {@code null}
 */
public record AiStatusResponse(boolean available, OffsetDateTime since) {
}
