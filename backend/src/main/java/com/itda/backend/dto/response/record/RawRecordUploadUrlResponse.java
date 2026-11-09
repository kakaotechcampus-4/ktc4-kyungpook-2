package com.itda.backend.dto.response.record;

import java.time.Instant;
import java.util.Map;

import com.itda.backend.domain.record.RawRecordUpload;
import com.itda.backend.service.storage.PresignedUpload;

/**
 * 업로드 주소 (#450). 브라우저는 {@code uploadUrl} 로 {@code method} 요청을 보내 파일 본문을 올리고, 끝나면
 * {@code POST /api/v1/raw-records/uploads/{uploadId}/complete} 를 부른다.
 *
 * @param uploadId  접수 id. 완료를 알릴 때 쓴다. 원본 기록 id 와 다른 번호다
 * @param uploadUrl 파일 본문을 올릴 주소. 운영은 S3 주소, 로컬은 {@code /api/...} 로 시작하는 BE 주소다
 * @param method    언제나 {@code PUT}
 * @param headers   그대로 붙일 헤더. 서명에 들어간 값이라 바꾸면 거절된다
 * @param expiresAt 이 시각이 지나면 주소를 쓸 수 없다. 업로드 주소부터 다시 받는다
 */
public record RawRecordUploadUrlResponse(
        Long uploadId, String uploadUrl, String method, Map<String, String> headers, Instant expiresAt) {

    public static RawRecordUploadUrlResponse of(RawRecordUpload upload, PresignedUpload presigned) {
        return new RawRecordUploadUrlResponse(
                upload.getId(), presigned.url(), "PUT", presigned.headers(), presigned.expiresAt());
    }
}
