package com.itda.backend.exception.auth;

import com.itda.backend.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {

    KAKAO_AUTH_FAILED(HttpStatus.BAD_REQUEST, "KAKAO_AUTH_FAILED", "카카오 인증에 실패했습니다."),
    KAKAO_SERVER_ERROR(HttpStatus.BAD_GATEWAY, "KAKAO_SERVER_ERROR", "카카오 서버에 문제가 발생했습니다."),
    KAKAO_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "KAKAO_UNAVAILABLE", "카카오 서버에 연결할 수 없습니다."),
    /** 카카오 연결 해제 웹훅의 어드민 키가 맞지 않거나 없다. 카카오가 보낸 요청이 아니다. */
    KAKAO_WEBHOOK_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "KAKAO_WEBHOOK_UNAUTHORIZED", "카카오 웹훅 인증에 실패했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
