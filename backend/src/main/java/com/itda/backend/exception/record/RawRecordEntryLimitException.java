package com.itda.backend.exception.record;

import com.itda.backend.global.exception.BusinessException;

/** 파일에서 나온 일지가 너무 많거나 하나가 너무 길어 파일을 받지 않았다 (#424). {@code 400 RAW_RECORD_ENTRY_LIMIT_EXCEEDED}. */
public class RawRecordEntryLimitException extends BusinessException {

    public RawRecordEntryLimitException(String message) {
        super(RawRecordErrorCode.ENTRY_LIMIT_EXCEEDED, message);
    }
}
