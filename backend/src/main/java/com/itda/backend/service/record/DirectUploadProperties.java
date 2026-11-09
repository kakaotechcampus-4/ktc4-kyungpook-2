package com.itda.backend.service.record;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.raw-record.direct-upload.*} 설정 — 브라우저가 저장소로 바로 올리는 업로드 (#450).
 *
 * @param urlTtl       업로드 주소를 쓸 수 있는 시간. 지나면 처음부터 다시 받는다
 * @param abandonAfter 완료되지 않은 접수를 버린 것으로 보는 시간. 정리 작업이 저장소 파일과 함께 지운다. 완료된 접수도 이 시간이
 *                     지나면 행만 지운다
 */
@ConfigurationProperties(prefix = "app.raw-record.direct-upload")
public record DirectUploadProperties(Duration urlTtl, Duration abandonAfter) {
}
