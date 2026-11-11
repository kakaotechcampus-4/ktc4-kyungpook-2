package com.itda.backend.exception.summary;

import com.itda.backend.global.exception.BusinessException;

public class SummaryRunValidationException extends BusinessException {

    public SummaryRunValidationException(String message) {
        super(SummaryErrorCode.INVALID_REQUEST, message);
    }
}
