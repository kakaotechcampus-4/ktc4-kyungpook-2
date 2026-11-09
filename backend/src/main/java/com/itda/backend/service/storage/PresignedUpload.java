package com.itda.backend.service.storage;

import java.time.Instant;
import java.util.Map;

/**
 * 브라우저가 파일 본문을 저장소로 바로 올릴 주소 (#450).
 *
 * @param url       {@code PUT} 할 주소. S3 면 서명이 붙은 S3 주소, 로컬이면 BE 의 {@code /api/...} 주소다
 * @param headers   요청에 그대로 붙일 헤더. 서명에 들어간 값이라 바꾸면 저장소가 거절한다
 * @param expiresAt 이 시각이 지나면 주소를 쓸 수 없다. 처음부터 다시 받는다
 */
public record PresignedUpload(String url, Map<String, String> headers, Instant expiresAt) {
}
