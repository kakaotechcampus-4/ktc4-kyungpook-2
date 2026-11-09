package com.itda.backend.global.exception;

import lombok.Getter;

/**
 * 오류 코드로 응답하는 예외의 부모. {@link GlobalExceptionHandler} 가 하나의 처리기로 받아 오류 코드의 상태와 고정 문구로
 * 응답한다. 예외 메시지(요청 값, id 같은 내부 사유)는 로그에만 남는다.
 *
 * <p>기능별 예외는 받을 수 있는 오류 코드 타입을 생성자로 좁히는 일만 한다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String detail, Throwable cause) {
        super(detail, cause);
        this.errorCode = errorCode;
    }
}
