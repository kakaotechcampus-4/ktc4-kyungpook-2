package com.itda.backend.service.record;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.raw-record.*} 설정 (#424).
 *
 * @param limits 파일 하나에서 나오는 일지의 상한
 * @param upload 업로드 바이트 읽기 · 글자 뽑기 · 나누기를 서버 전체에서 동시에 몇 개까지 하는지
 */
@ConfigurationProperties(prefix = "app.raw-record")
public record RawRecordProperties(Limits limits, Upload upload) {

    /**
     * @param maxEntriesPerFile 파일 하나에서 나오는 일지 수의 상한. 넘으면 파일을 받지 않는다. 일지 하나의 길이 상한은 화면과 맞춰야
     *                          해서 설정이 아니라 {@code JournalEntry.MAX_CONTENT_LENGTH} 다
     */
    public record Limits(int maxEntriesPerFile) {
    }

    /**
     * @param maxConcurrent 동시에 바이트를 읽고 글자를 뽑는 업로드 수. 파일마다 최대 20MB 를 힙에 올리고 docx 는 그보다 크게 풀린다
     * @param maxWaiting    자리를 기다릴 수 있는 업로드 수. 넘으면 기다리지 않고 바로 거절한다
     * @param maxWait       자리를 기다리는 최대 시간
     */
    public record Upload(int maxConcurrent, int maxWaiting, Duration maxWait) {
    }
}
