package com.itda.backend.exception.matching;

import com.itda.backend.global.exception.BusinessException;

public class MatchingResultValidationException extends BusinessException {

    public MatchingResultValidationException(String message) {
        super(MatchingResultErrorCode.INVALID_REQUEST, message);
    }
}
