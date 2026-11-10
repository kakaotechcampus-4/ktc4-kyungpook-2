package com.itda.backend.service.record;

import java.time.LocalDateTime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.itda.backend.service.record.RawRecordUploadService.Swept;
import com.itda.backend.service.storage.RawFileStorage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
