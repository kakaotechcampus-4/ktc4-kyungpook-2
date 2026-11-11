package com.itda.backend.domain.matching;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MultiReason {
    AMBIGUOUS_IDENTITY,
    CO_MENTION;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static MultiReason fromJson(String value) {
        return valueOf(value.toUpperCase());
    }
}
