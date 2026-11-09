package com.itda.backend.exception.record;

import com.itda.backend.global.exception.BusinessException;

public class RawRecordUploadNotFoundException extends BusinessException {

    public RawRecordUploadNotFoundException(Long id) {
        super(RawRecordErrorCode.UPLOAD_NOT_FOUND, "raw record upload not found: " + id);
    }
}
