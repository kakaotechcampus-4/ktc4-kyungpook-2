package com.itda.backend.exception.sharing;

import com.itda.backend.global.exception.BusinessException;

public class SharingException extends BusinessException {

    public SharingException(SharingErrorCode errorCode, Long insightId) {
        this(errorCode, "insightId=" + insightId);
    }

    public SharingException(SharingErrorCode errorCode, String detail) {
        super(errorCode, errorCode.getCode() + " " + detail);
    }
}
