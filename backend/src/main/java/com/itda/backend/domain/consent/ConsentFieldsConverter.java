package com.itda.backend.domain.consent;

import jakarta.persistence.Converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.itda.backend.global.json.JsonListConverter;

@Converter
public class ConsentFieldsConverter extends JsonListConverter<ConsentField> {

    public ConsentFieldsConverter() {
        super(new TypeReference<>() {
        }, "동의 범위");
    }
}
