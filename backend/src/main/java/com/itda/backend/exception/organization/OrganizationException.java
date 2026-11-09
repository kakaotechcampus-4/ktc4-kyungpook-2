package com.itda.backend.exception.organization;

import com.itda.backend.global.exception.BusinessException;

public class OrganizationException extends BusinessException {

    public OrganizationException(OrganizationErrorCode errorCode) {
        super(errorCode);
    }
}
