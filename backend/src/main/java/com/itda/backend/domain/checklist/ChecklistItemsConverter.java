package com.itda.backend.domain.checklist;

import java.util.List;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code checklist.items} 를 JSON 문자열 배열로 저장한다. 다른 JSON 컬럼과 같이 TEXT 에 넣는다
 * (DB 스키마 §0.3 머리말 — JSON 컬럼은 TEXT 로 맞췄다).
 */
@Converter
public class ChecklistItemsConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<String> items) {
        try {
            return MAPPER.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("준비물·확인사항 항목을 JSON 으로 바꾸지 못했습니다.", e);
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String json) {
        try {
            return List.copyOf(MAPPER.readValue(json, TYPE));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("준비물·확인사항 항목 JSON 을 읽지 못했습니다.", e);
        }
    }
}
