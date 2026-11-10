package com.itda.backend.domain.child;

import jakarta.persistence.Converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.itda.backend.global.json.JsonListConverter;

@Converter
public class AllergiesConverter extends JsonListConverter<String> {

    public AllergiesConverter() {
        super(new TypeReference<>() {
        }, "알레르기 목록");
    }
}
