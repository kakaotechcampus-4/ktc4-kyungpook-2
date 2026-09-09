package com.itda.backend.exception;

public class RawRecordNotFoundException extends RuntimeException {

    public RawRecordNotFoundException(Long id) {
        super("raw record not found: " + id);
    }
}
