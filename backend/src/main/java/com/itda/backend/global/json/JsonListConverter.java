package com.itda.backend.global.json;

import java.util.List;

import jakarta.persistence.AttributeConverter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 목록 하나를 JSON 배열 문자열로 TEXT 컬럼에 저장하는 변환기의 부모 (DB 스키마 §0.3 머리말 — JSON 컬럼은 TEXT 로 맞췄다).
 * 하위 클래스는 원소 타입과 오류 문구에 쓸 이름만 넘긴다. 읽은 목록은 고칠 수 없다.
 */
public abstract class JsonListConverter<T> implements AttributeConverter<List<T>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TypeReference<List<T>> type;
    private final String label;

    protected JsonListConverter(TypeReference<List<T>> type, String label) {
        this.type = type;
        this.label = label;
    }

    @Override
    public String convertToDatabaseColumn(List<T> values) {
        try {
            return MAPPER.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(label + " 을 JSON 으로 바꾸지 못했습니다.", e);
        }
    }

    @Override
    public List<T> convertToEntityAttribute(String json) {
        try {
            return List.copyOf(MAPPER.readValue(json, type));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 " + label + " 을 읽지 못했습니다.", e);
        }
    }
}
