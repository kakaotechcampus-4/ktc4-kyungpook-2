package com.itda.backend.exception.validation;

public class ValidationResultNotFoundException extends RuntimeException {

    public ValidationResultNotFoundException(Long id) {
        super("validation result not found: " + id);
    }
}
