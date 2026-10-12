package com.itda.backend.exception;

public class ChildException extends RuntimeException {

    private final ChildErrorCode errorCode;

    public ChildException(ChildErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public ChildErrorCode getErrorCode() {
        return errorCode;
    }
}
