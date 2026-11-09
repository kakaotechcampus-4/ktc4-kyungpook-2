package com.itda.backend.exception.record;

import com.itda.backend.global.exception.BusinessException;

public class RawRecordValidationException extends BusinessException {

    public RawRecordValidationException(String message) {
        super(RawRecordErrorCode.INVALID_REQUEST, message);
    }
}
