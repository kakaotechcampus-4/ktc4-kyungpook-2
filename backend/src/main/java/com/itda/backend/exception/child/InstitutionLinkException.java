package com.itda.backend.exception.child;

public class InstitutionLinkException extends RuntimeException {

    private final InstitutionLinkErrorCode errorCode;

    public InstitutionLinkException(InstitutionLinkErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public InstitutionLinkErrorCode getErrorCode() {
        return errorCode;
    }
}
