package com.itda.backend.domain.consent;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 한 아이·한 기관에 대한 동의 상태. 저장하지 않고 {@code sharing_consent} 행에서 계산한다 (DB 스키마 §5.5).
 *
 * <pre>
 * GRANTED      그 기관에 ACTIVE 동의가 있다
 * REVOKED      동의 행은 있지만 모두 REVOKED 다
 * NOT_GRANTED  그 기관에 동의 행이 없다
 * </pre>
 */
public enum ConsentState {
    GRANTED,
    REVOKED,
    NOT_GRANTED;

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }
}
