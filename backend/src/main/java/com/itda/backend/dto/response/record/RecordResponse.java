package com.itda.backend.dto.response.record;

public record RecordResponse(String id, String fileName, String preview, String capturedAt, String fullContent) {

    private static final int PREVIEW_LENGTH = 60;

    public static RecordResponse of(String id, String fileName, String content, String capturedAt) {
        return new RecordResponse(id, fileName, preview(content), capturedAt, content);
    }

    private static String preview(String content) {
        if (content == null) {
            return null;
        }
        return content.length() <= PREVIEW_LENGTH ? content : content.substring(0, PREVIEW_LENGTH) + "…";
    }
}
