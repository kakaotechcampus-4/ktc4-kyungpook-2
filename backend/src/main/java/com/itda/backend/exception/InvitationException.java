package com.itda.backend.exception;

public class InvitationException extends RuntimeException {

    private final InvitationErrorCode errorCode;

    public InvitationException(InvitationErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public InvitationErrorCode getErrorCode() {
        return errorCode;
    }
}
