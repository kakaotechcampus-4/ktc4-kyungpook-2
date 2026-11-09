package com.itda.backend.global.exception;

public record ErrorResponse(String result, String code, String message) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse("FAIL", errorCode.getCode(), errorCode.getMessage());
    }
}
