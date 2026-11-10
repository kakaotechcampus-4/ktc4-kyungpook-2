package com.itda.backend.exception.matching;

import com.itda.backend.global.exception.BusinessException;

public class MatchingResultNotFoundException extends BusinessException {

    public MatchingResultNotFoundException(Long id) {
        super(MatchingResultErrorCode.NOT_FOUND, "matching result not found: " + id);
    }
}
