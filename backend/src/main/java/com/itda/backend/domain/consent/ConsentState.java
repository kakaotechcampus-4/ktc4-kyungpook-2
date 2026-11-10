package com.itda.backend.domain.consent;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ConsentState {
    GRANTED,
    REVOKED,
    NOT_GRANTED;

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }
}
