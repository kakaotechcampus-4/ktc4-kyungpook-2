package com.itda.backend.exception.validation;

public class ValidationResultValidationException extends RuntimeException {

    private final ValidationResultErrorCode errorCode;

    public ValidationResultValidationException(String message) {
        this(ValidationResultErrorCode.INVALID_REQUEST, message);
    }

    public ValidationResultValidationException(ValidationResultErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ValidationResultErrorCode getErrorCode() {
        return errorCode;
    }
}
