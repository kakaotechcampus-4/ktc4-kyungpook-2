package com.itda.backend.exception;

public class ValidationResultNotFoundException extends RuntimeException {

    public ValidationResultNotFoundException(Long id) {
        super("validation result not found: " + id);
    }
}
