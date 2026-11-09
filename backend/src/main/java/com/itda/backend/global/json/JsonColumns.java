package com.itda.backend.global.json;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * TEXT 로 저장한 JSON 컬럼을 너그럽게 읽는다. 대부분 AI 가 쓴 값이라 비었거나 모양이 다르거나 깨져 있을 수 있다.
 * 한 행이 깨졌다고 목록 조회 전체를 500 내지 않는다 — 빈 값으로 읽고, 깨진 경우만 컬럼과 행 id 를 경고 로그로 남긴다.
 */
@Slf4j
public final class JsonColumns {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonColumns() {
    }

    /** 비었으면 {@code null}, 깨졌으면 {@code null} 과 경고 로그. 모양은 보지 않는다. */
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

    /** 배열이 아니면(비었거나 깨졌어도) 빈 배열이다. */
    public static JsonNode readArray(String raw, String column, Long rowId) {
        JsonNode node = readTree(raw, column, rowId);
        return node != null && node.isArray() ? node : MAPPER.createArrayNode();
    }

    /** 배열에서 정수로 읽을 수 있는 원소만, 순서대로. */
    public static List<Long> readLongs(String raw, String column, Long rowId) {
        List<Long> values = new ArrayList<>();
        for (JsonNode node : readArray(raw, column, rowId)) {
            if (node.canConvertToLong()) {
                values.add(node.asLong());
            }
        }
        return values;
    }

    /** 배열에서 문자열 원소만, 순서대로. */
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
