package com.itda.backend.exception.consent;

import org.springframework.http.HttpStatus;

import com.itda.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ConsentErrorCode implements ErrorCode {

    CONSENT_TARGET_NOT_FOUND(HttpStatus.NOT_FOUND, "CONSENT_TARGET_NOT_FOUND", "동의할 기관을 찾을 수 없습니다."),

    CHILD_NOT_LINKED(HttpStatus.FORBIDDEN, "FORBIDDEN", "권한이 없습니다."),

    INVALID_CONSENT_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "잘못된 요청입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
