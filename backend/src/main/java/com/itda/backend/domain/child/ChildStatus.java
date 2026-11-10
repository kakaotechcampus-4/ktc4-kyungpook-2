package com.itda.backend.domain.child;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ChildStatus {
    PENDING_CONSENT,
    ACTIVE,
    SUSPENDED;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static ChildStatus fromJson(String value) {
        return valueOf(value.toUpperCase());
    }
}
