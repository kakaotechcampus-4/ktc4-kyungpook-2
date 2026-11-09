package com.itda.backend.domain.consent;

import jakarta.persistence.Converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.itda.backend.global.json.JsonListConverter;

/**
 * {@code sharing_consent.scope} 를 JSON 배열 문자열로 저장한다. 다른 JSON 컬럼과 같이 TEXT 에 넣는다
 * (DB 스키마 §0.3 머리말 — JSON 컬럼은 TEXT 로 맞췄다). 값은 {@link ConsentField#toJson()} 의 소문자다.
 */
@Converter
public class ConsentFieldsConverter extends JsonListConverter<ConsentField> {

    public ConsentFieldsConverter() {
        super(new TypeReference<>() {
        }, "동의 범위");
    }
}
