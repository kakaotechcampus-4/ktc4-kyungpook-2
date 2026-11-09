package com.itda.backend.global.json;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class JsonColumns {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonColumns() {
    }

    public static JsonNode readTree(String raw, String column, Long rowId) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            log.warn("failed to parse {} id={}", column, rowId, e);
            return null;
        }
    }

    public static JsonNode readArray(String raw, String column, Long rowId) {
        JsonNode node = readTree(raw, column, rowId);
        return node != null && node.isArray() ? node : MAPPER.createArrayNode();
    }

    public static List<Long> readLongs(String raw, String column, Long rowId) {
        List<Long> values = new ArrayList<>();
        for (JsonNode node : readArray(raw, column, rowId)) {
            if (node.canConvertToLong()) {
                values.add(node.asLong());
            }
        }
        return values;
    }

    public static List<String> readTexts(String raw, String column, Long rowId) {
        List<String> values = new ArrayList<>();
        for (JsonNode node : readArray(raw, column, rowId)) {
            if (node.isTextual()) {
                values.add(node.asText());
            }
        }
        return values;
    }
}
