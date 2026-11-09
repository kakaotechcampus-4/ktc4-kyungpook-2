package com.itda.backend.domain.checklist;

import jakarta.persistence.Converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.itda.backend.global.json.JsonListConverter;

/**
 * {@code checklist.items} 를 JSON 문자열 배열로 저장한다. 다른 JSON 컬럼과 같이 TEXT 에 넣는다
 * (DB 스키마 §0.3 머리말 — JSON 컬럼은 TEXT 로 맞췄다).
 */
@Converter
public class ChecklistItemsConverter extends JsonListConverter<String> {

    public ChecklistItemsConverter() {
        super(new TypeReference<>() {
        }, "준비물·확인사항 항목");
    }
}
