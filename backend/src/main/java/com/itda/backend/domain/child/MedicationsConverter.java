package com.itda.backend.domain.child;

import jakarta.persistence.Converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.itda.backend.domain.child.ChildCareInfo.Medication;
import com.itda.backend.global.json.JsonListConverter;

@Converter
public class MedicationsConverter extends JsonListConverter<Medication> {

    public MedicationsConverter() {
        super(new TypeReference<>() {
        }, "복용약 목록");
    }
}
