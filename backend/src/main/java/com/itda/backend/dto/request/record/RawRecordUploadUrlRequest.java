package com.itda.backend.dto.request.record;

public record RawRecordUploadUrlRequest(String filename, String contentType, Long size) {
}
