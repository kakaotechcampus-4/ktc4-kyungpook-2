package com.itda.backend.exception.validation;

import com.itda.backend.global.exception.BusinessException;

public class ValidationResultValidationException extends BusinessException {

    public ValidationResultValidationException(String message) {
        this(ValidationResultErrorCode.INVALID_REQUEST, message);
    }

    public ValidationResultValidationException(ValidationResultErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
