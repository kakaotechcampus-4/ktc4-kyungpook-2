package com.itda.backend.exception.checklist;

import com.itda.backend.global.exception.BusinessException;

public class ChecklistException extends BusinessException {

    public ChecklistException(ChecklistErrorCode errorCode) {
        super(errorCode);
    }
}
