package com.itda.backend.exception.record;

import com.itda.backend.global.exception.BusinessException;

/** 업로드 주소를 받으려는 파일이 상한보다 크다 (#450). {@code 413 RAW_RECORD_FILE_TOO_LARGE} — 한 번에 올리는 업로드와 같은 코드다. */
public class RawRecordTooLargeException extends BusinessException {

    public RawRecordTooLargeException(String message) {
        super(RawRecordErrorCode.FILE_TOO_LARGE, message);
    }
}
