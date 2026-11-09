package com.itda.backend.service.record;

import java.time.LocalDateTime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.itda.backend.service.record.RawRecordUploadService.Swept;
import com.itda.backend.service.storage.RawFileStorage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 하루 한 번 오래된 업로드 접수를 지운다 (#450). 선생님이 올리다 창을 닫으면 완료가 오지 않아 접수와 저장소 파일이 남는다 —
 * 하루가 지난 것은 버린 것으로 보고 파일과 함께 지운다. 업로드 주소는 10분이면 만료돼 하루면 넉넉하다.
 *
 * <p>저장소 파일은 DB 가 커밋된 뒤에 지운다 ({@link RemovedRecordPurgeWorker} 와 같은 이유). 파일 삭제가 실패하면 행 없는 파일이
 * 남는데, 로그로만 알린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.raw-record.direct-upload.sweeper", name = "enabled", havingValue = "true")
public class AbandonedUploadSweeper {

    private final RawRecordUploadService rawRecordUploadService;
    private final RawFileStorage rawFileStorage;

    @Scheduled(cron = "${app.raw-record.direct-upload.sweeper.cron}", zone = "Asia/Seoul")
    public void run() {
        LocalDateTime now = LocalDateTime.now();
        Swept swept;
        int abandoned = 0;
        do {
            swept = rawRecordUploadService.sweepBatch(now);
            swept.abandonedKeys().forEach(this::deleteFile);
            abandoned += swept.abandonedKeys().size();
        } while (swept.uploads() == RawRecordUploadService.SWEEP_BATCH_SIZE);
        if (abandoned > 0) {
            log.info("swept {} abandoned raw record upload(s)", abandoned);
        }
    }

    private void deleteFile(String storedKey) {
        try {
            rawFileStorage.delete(storedKey);
        } catch (RuntimeException e) {
            log.warn("abandoned upload file could not be deleted storedKey={}", storedKey, e);
        }
    }
}
