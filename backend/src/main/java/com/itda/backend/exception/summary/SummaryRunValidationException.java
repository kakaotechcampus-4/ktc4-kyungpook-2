package com.itda.backend.exception.summary;

import com.itda.backend.global.exception.BusinessException;

/** [요약] 요청을 받을 수 없다 (그 아이·날짜에 요약할 새 일지가 없음). */
public class SummaryRunValidationException extends BusinessException {

    public SummaryRunValidationException(String message) {
        super(SummaryErrorCode.INVALID_REQUEST, message);
    }
}
