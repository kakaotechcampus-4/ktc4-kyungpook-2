package com.itda.backend.exception.record;

import com.itda.backend.global.exception.BusinessException;

public class RawRecordNotFoundException extends BusinessException {

    public RawRecordNotFoundException(Long id) {
        super(RawRecordErrorCode.NOT_FOUND, "raw record not found: " + id);
    }
}
