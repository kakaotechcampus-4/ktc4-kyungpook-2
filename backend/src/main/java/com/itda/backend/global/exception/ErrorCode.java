package com.itda.backend.global.exception;

import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;

public interface ErrorCode {

    HttpStatus getStatus();

    String getCode();

    String getMessage();

    String name();

    default Level logLevel() {
        return getStatus().is5xxServerError() ? Level.ERROR : Level.WARN;
    }
}
