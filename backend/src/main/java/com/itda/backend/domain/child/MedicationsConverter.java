package com.itda.backend.domain.child;

import java.util.List;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.domain.child.ChildCareInfo.Medication;

/**
 * {@code child_care_info.medications} 를 {@code [{"name": …, "time": …}]} JSON 문자열로 저장한다. 다른 JSON 컬럼과
 * 같이 TEXT 에 넣는다 (DB 스키마 §0.3 머리말).
 */
@Converter
public class MedicationsConverter implements AttributeConverter<List<Medication>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Medication>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<Medication> medications) {
        try {
            return MAPPER.writeValueAsString(medications);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("복용약 목록을 JSON 으로 바꾸지 못했습니다.", e);
        }
    }

    @Override
    public List<Medication> convertToEntityAttribute(String json) {
        try {
            return List.copyOf(MAPPER.readValue(json, TYPE));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 복용약 목록을 읽지 못했습니다.", e);
        }
    }
}
