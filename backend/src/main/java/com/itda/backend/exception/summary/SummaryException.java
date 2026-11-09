package com.itda.backend.exception.summary;

import com.itda.backend.global.exception.BusinessException;

public class SummaryException extends BusinessException {

    public SummaryException(SummaryErrorCode errorCode, Long summaryId) {
        this(errorCode, "summaryId=" + summaryId);
    }

    public SummaryException(SummaryErrorCode errorCode, String detail) {
        super(errorCode, errorCode.getCode() + " " + detail);
    }
}
