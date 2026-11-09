package com.itda.backend.exception.summary;

import com.itda.backend.global.exception.BusinessException;

public class SummaryChildNotFoundException extends BusinessException {

    public SummaryChildNotFoundException(Long childId) {
        super(SummaryErrorCode.CHILD_NOT_FOUND, "child not found in institution: " + childId);
    }
}
