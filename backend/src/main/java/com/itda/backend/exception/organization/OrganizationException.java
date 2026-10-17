package com.itda.backend.exception.organization;

public class OrganizationException extends RuntimeException {

    private final OrganizationErrorCode errorCode;

    public OrganizationException(OrganizationErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public OrganizationErrorCode getErrorCode() {
        return errorCode;
    }
}
