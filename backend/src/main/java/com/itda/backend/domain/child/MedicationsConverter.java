package com.itda.backend.domain.child;

import jakarta.persistence.Converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.itda.backend.domain.child.ChildCareInfo.Medication;
import com.itda.backend.global.json.JsonListConverter;

/**
 * {@code child_care_info.medications} 를 {@code [{"name": …, "time": …}]} JSON 문자열로 저장한다. 다른 JSON 컬럼과
 * 같이 TEXT 에 넣는다 (DB 스키마 §0.3 머리말).
 */
@Converter
public class MedicationsConverter extends JsonListConverter<Medication> {

    public MedicationsConverter() {
        super(new TypeReference<>() {
        }, "복용약 목록");
    }
}
