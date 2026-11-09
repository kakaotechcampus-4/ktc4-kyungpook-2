package com.itda.backend.exception.validation;

import com.itda.backend.global.exception.BusinessException;

public class ValidationResultNotFoundException extends BusinessException {

    public ValidationResultNotFoundException(Long id) {
        super(ValidationResultErrorCode.NOT_FOUND, "validation result not found: " + id);
    }
}
