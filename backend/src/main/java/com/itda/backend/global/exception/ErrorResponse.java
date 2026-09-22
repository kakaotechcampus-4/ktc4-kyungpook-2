package com.itda.backend.global.exception;

/**
 * docs/api/api-conventions.md 의 실패 응답 규격 — { result, code, message }.
 */
public record ErrorResponse(String result, String code, String message) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse("FAIL", errorCode.getCode(), errorCode.getMessage());
    }
}
