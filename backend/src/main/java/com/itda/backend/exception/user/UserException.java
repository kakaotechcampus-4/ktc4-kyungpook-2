package com.itda.backend.exception.user;

import com.itda.backend.global.exception.BusinessException;

public class UserException extends BusinessException {

    public UserException(UserErrorCode errorCode) {
        super(errorCode);
    }
}
