package com.itda.backend.domain.consent;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 보호자가 기관에 공유를 허락하는 항목. FE 의 {@code ConsentField} 와 같은 값이다.
 *
 * <p>JSON 과 {@code sharing_consent.scope} 에는 소문자({@code daily_summary})로 들어간다.
 * 모르는 값이면 {@link #fromJson} 이 실패해 요청 전체가 400 이 된다.
 */
public enum ConsentField {
    DAILY_SUMMARY,
    WEEKLY_INSIGHT;

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static ConsentField fromJson(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
