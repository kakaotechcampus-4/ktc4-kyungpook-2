package com.itda.backend.domain.user;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum TermKey {
    TOS,
    PRIVACY,
    ORG_TOS,
    ORG_PRIVACY,
    ORG_CHILD_DATA;

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static TermKey fromJson(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
