package com.itda.backend.exception.checklist;

public class ChecklistException extends RuntimeException {

    private final ChecklistErrorCode errorCode;

    public ChecklistException(ChecklistErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public ChecklistErrorCode getErrorCode() {
        return errorCode;
    }
}
