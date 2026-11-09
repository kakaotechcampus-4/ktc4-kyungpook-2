package com.itda.backend.dto.request.record;

/**
 * 업로드 주소 요청 (#450). 파일 본문은 보내지 않는다 — 이름 · 형식 · 크기만 먼저 알린다.
 *
 * <p>값 검사는 한 번에 올리는 업로드와 같은 기준으로 서비스가 한다(같은 {@code RAW_RECORD_INVALID_REQUEST} 로 응답하려고).
 *
 * @param filename    파일 이름. 확장자로 형식을 본다
 * @param contentType 브라우저가 알려 준 형식({@code File.type}). S3 에 올릴 때도 이 값을 그대로 쓴다
 * @param size        바이트 수({@code File.size}). 주소에 서명되어 다른 크기는 받지 않는다
 */
public record RawRecordUploadUrlRequest(String filename, String contentType, Long size) {
}
