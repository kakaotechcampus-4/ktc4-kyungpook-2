package com.itda.backend.domain.user;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 보호자가 동의하는 서비스 약관. 지금은 둘 다 필수다.
 *
 * <p>JSON 에는 소문자({@code tos})로, {@code terms_agreement.term_key} 에는 이름 그대로({@code TOS}) 들어간다.
 * 모르는 값이면 {@link #fromJson} 이 실패해 요청 전체가 400 이 된다.
 *
 * <p>값을 늘리면 배포 DB 의 {@code term_key} CHECK 제약을 지워야 한다 (DB 스키마 §11.4).
 */
public enum TermKey {
    /** 서비스 이용약관 */
    TOS,
    /** 개인정보와 민감정보 수집 */
    PRIVACY;

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static TermKey fromJson(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
