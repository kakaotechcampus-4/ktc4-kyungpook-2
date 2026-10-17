package com.itda.backend.domain.consent;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** G-41 요청의 {@code action}. 저장하지 않고 요청에서만 쓴다. */
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
