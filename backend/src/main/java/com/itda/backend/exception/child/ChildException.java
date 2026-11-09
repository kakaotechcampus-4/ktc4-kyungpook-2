package com.itda.backend.exception.child;

import com.itda.backend.global.exception.BusinessException;

public class ChildException extends BusinessException {

    public ChildException(ChildErrorCode errorCode) {
        super(errorCode);
    }
}
