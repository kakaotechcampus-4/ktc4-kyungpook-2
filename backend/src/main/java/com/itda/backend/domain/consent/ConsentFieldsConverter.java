package com.itda.backend.domain.consent;

import java.util.List;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code sharing_consent.scope} 를 JSON 배열 문자열로 저장한다. 다른 JSON 컬럼과 같이 TEXT 에 넣는다
 * (DB 스키마 §0.3 머리말 — JSON 컬럼은 TEXT 로 맞췄다). 값은 {@link ConsentField#toJson()} 의 소문자다.
 */
@Converter
public class ConsentFieldsConverter implements AttributeConverter<List<ConsentField>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<ConsentField>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<ConsentField> fields) {
        try {
            return MAPPER.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("동의 범위를 JSON 으로 바꾸지 못했습니다.", e);
        }
    }

    @Override
    public List<ConsentField> convertToEntityAttribute(String json) {
        try {
            return List.copyOf(MAPPER.readValue(json, TYPE));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 동의 범위를 읽지 못했습니다.", e);
        }
    }
}
