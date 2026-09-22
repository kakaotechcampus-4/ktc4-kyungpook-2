package com.itda.backend.exception;

public class MatchingResultNotFoundException extends RuntimeException {

    public MatchingResultNotFoundException(Long id) {
        super("matching result not found: " + id);
    }
}
