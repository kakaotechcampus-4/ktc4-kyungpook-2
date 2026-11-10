package com.itda.backend.exception.record;

import com.itda.backend.global.exception.BusinessException;

public class RawRecordEntryLimitException extends BusinessException {

    public RawRecordEntryLimitException(String message) {
        super(RawRecordErrorCode.ENTRY_LIMIT_EXCEEDED, message);
    }
}
