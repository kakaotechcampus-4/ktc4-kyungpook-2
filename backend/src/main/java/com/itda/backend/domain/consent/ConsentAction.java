package com.itda.backend.domain.consent;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ConsentAction {
    GRANT,
    REVOKE;

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static ConsentAction fromJson(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
