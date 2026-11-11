package com.itda.backend.exception.record;

import com.itda.backend.global.exception.BusinessException;

public class RawRecordStorageException extends BusinessException {

    public RawRecordStorageException(String message, Throwable cause) {
        super(RawRecordErrorCode.STORAGE_FAILED, message, cause);
    }
}
