package com.itda.backend.exception.record;

import com.itda.backend.global.exception.BusinessException;

public class RawRecordUploadBusyException extends BusinessException {

    public RawRecordUploadBusyException(String message) {
        super(RawRecordErrorCode.UPLOAD_BUSY, message);
    }
}
