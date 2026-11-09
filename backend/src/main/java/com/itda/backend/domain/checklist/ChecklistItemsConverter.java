package com.itda.backend.domain.checklist;

import jakarta.persistence.Converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.itda.backend.global.json.JsonListConverter;

@Converter
public class ChecklistItemsConverter extends JsonListConverter<String> {

    public ChecklistItemsConverter() {
        super(new TypeReference<>() {
        }, "준비물·확인사항 항목");
    }
}
