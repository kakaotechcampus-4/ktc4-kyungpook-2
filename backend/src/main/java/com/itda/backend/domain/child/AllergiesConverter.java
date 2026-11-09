package com.itda.backend.domain.child;

import jakarta.persistence.Converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.itda.backend.global.json.JsonListConverter;

/**
 * {@code child_care_info.allergies} 를 JSON 문자열 배열로 저장한다. 다른 JSON 컬럼과 같이 TEXT 에 넣는다
 * (DB 스키마 §0.3 머리말 — JSON 컬럼은 TEXT 로 맞췄다).
 */
@Converter
public class AllergiesConverter extends JsonListConverter<String> {

    public AllergiesConverter() {
        super(new TypeReference<>() {
        }, "알레르기 목록");
    }
}
