package com.itda.backend.exception.matching;

public class MatchingResultNotFoundException extends RuntimeException {

    public MatchingResultNotFoundException(Long id) {
        super("matching result not found: " + id);
    }
}
