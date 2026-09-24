package com.itda.backend.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

// AI/matching/schemas.py의 MultiReason과 값 대응. status=MULTI일 때만 채워진다.
// MatchingStatus와 같은 이유로 JSON은 소문자, DB/Java 상수는 대문자.
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
