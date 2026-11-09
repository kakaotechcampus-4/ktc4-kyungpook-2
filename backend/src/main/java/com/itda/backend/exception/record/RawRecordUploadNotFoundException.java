package com.itda.backend.exception.record;

/** 없는 접수이거나 다른 기관의 접수다 (#450). {@code 404 RAW_RECORD_UPLOAD_NOT_FOUND}. 있는지조차 알리지 않는다. */
public class RawRecordUploadNotFoundException extends RuntimeException {

    public RawRecordUploadNotFoundException(Long id) {
        super("raw record upload not found: " + id);
    }
}
