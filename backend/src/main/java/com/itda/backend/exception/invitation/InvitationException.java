package com.itda.backend.exception.invitation;

import com.itda.backend.global.exception.BusinessException;

public class InvitationException extends BusinessException {

    public InvitationException(InvitationErrorCode errorCode) {
        super(errorCode);
    }
}
