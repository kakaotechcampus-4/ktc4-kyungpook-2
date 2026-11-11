package com.itda.backend.exception.record;

import com.itda.backend.global.exception.BusinessException;

public class RawRecordUploadNotReadyException extends BusinessException {

    public RawRecordUploadNotReadyException(Long id) {
        super(RawRecordErrorCode.UPLOAD_NOT_READY, "raw record upload has no stored file yet: " + id);
    }
}
