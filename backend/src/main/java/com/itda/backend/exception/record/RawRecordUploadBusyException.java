package com.itda.backend.exception.record;

import com.itda.backend.global.exception.BusinessException;

/** 업로드 처리 자리와 기다리는 줄이 꽉 찼다 (#424). {@code 429 RAW_RECORD_UPLOAD_BUSY} 로 응답한다. */
public class RawRecordUploadBusyException extends BusinessException {

    public RawRecordUploadBusyException(String message) {
        super(RawRecordErrorCode.UPLOAD_BUSY, message);
    }
}
