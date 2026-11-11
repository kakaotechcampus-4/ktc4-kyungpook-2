package com.itda.backend.exception.record;

import com.itda.backend.global.exception.BusinessException;

public class RawRecordTooLargeException extends BusinessException {

    public RawRecordTooLargeException(String message) {
        super(RawRecordErrorCode.FILE_TOO_LARGE, message);
    }
}
