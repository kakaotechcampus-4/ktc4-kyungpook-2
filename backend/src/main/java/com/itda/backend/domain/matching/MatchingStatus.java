package com.itda.backend.domain.matching;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MatchingStatus {
    AUTO,
    REVIEW,
    MULTI,
    UNMATCHED,
    FAILED;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static MatchingStatus fromJson(String value) {
        return valueOf(value.toUpperCase());
    }
}
