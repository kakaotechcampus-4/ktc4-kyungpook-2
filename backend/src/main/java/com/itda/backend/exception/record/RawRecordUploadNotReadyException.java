package com.itda.backend.exception.record;

/** 완료를 알렸지만 저장소에 아직 파일이 없다 (#450). {@code 409 RAW_RECORD_UPLOAD_NOT_READY}. 접수는 남는다. */
public class RawRecordUploadNotReadyException extends RuntimeException {

    public RawRecordUploadNotReadyException(Long id) {
        super("raw record upload has no stored file yet: " + id);
    }
}
