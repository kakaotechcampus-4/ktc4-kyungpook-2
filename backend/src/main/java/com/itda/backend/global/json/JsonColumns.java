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
            log.warn("failed to parse {} id={} ({})", column, rowId, e.getClass().getSimpleName());
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

    public static List<Long> readLongsAllowingText(String raw, String column, Long rowId) {
        List<Long> values = new ArrayList<>();
        for (JsonNode node : readArray(raw, column, rowId)) {
            Long value = toLong(node);
            if (value == null) {
                log.warn("non-numeric value in {} id={}, ignoring the whole array", column, rowId);
                return new ArrayList<>();
            }
            values.add(value);
        }
        return values;
    }

    public static List<Long> readLongValuesAllowingText(String raw, String column, Long rowId) {
        JsonNode node = readTree(raw, column, rowId);
        if (node == null || !node.isContainerNode()) {
            return new ArrayList<>();
        }
        return collectLongs(node, column, rowId);
    }

    private static List<Long> collectLongs(Iterable<JsonNode> nodes, String column, Long rowId) {
        List<Long> values = new ArrayList<>();
        for (JsonNode node : nodes) {
            Long value = toLong(node);
            if (value != null) {
                values.add(value);
            } else if (node.isTextual()) {
                log.warn("non-numeric value in {} id={}", column, rowId);
            }
        }
        return values;
    }

    private static Long toLong(JsonNode node) {
        if (node.isNumber()) {
            return node.canConvertToLong() ? node.asLong() : null;
        }
        if (node.isTextual()) {
            try {
                return Long.parseLong(node.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
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
