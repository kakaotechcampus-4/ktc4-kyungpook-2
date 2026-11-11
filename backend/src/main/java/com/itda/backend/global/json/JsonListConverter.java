package com.itda.backend.global.json;

import java.util.List;

import jakarta.persistence.AttributeConverter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

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
