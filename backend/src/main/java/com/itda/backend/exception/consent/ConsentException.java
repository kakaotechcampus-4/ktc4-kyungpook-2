package com.itda.backend.exception.consent;

import com.itda.backend.global.exception.BusinessException;

public class ConsentException extends BusinessException {

    public ConsentException(ConsentErrorCode errorCode) {
        super(errorCode);
    }
}
