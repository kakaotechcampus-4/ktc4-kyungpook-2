package com.itda.backend.exception.child;

import com.itda.backend.global.exception.BusinessException;

public class InstitutionLinkException extends BusinessException {

    public InstitutionLinkException(InstitutionLinkErrorCode errorCode) {
        super(errorCode);
    }
}
