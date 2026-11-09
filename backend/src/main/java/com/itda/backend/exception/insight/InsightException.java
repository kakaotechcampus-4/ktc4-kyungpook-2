package com.itda.backend.exception.insight;

import com.itda.backend.global.exception.BusinessException;

public class InsightException extends BusinessException {

    public InsightException(InsightErrorCode errorCode, Long insightId) {
        this(errorCode, "insightId=" + insightId);
    }

    public InsightException(InsightErrorCode errorCode, String detail) {
        super(errorCode, errorCode.getCode() + " " + detail);
    }
}
