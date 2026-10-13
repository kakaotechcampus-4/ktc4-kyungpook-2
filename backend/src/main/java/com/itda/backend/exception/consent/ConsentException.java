package com.itda.backend.exception.consent;

public class ConsentException extends RuntimeException {

    private final ConsentErrorCode errorCode;

    public ConsentException(ConsentErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public ConsentErrorCode getErrorCode() {
        return errorCode;
    }
}
