package com.itda.backend.service.record;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.itda.backend.service.record.RemovedRecordPurgeService.Purged;
import com.itda.backend.service.storage.RawFileStorage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.purge.worker", name = "enabled", havingValue = "true")
public class RemovedRecordPurgeWorker {

    private final RemovedRecordPurgeService purgeService;
    private final RawFileStorage rawFileStorage;

    @Scheduled(cron = "${app.purge.worker.cron}", zone = "Asia/Seoul")
    public void run() {
        Purged purged;
        do {
            purged = purgeService.purgeBatch();
            purged.storedKeys().forEach(this::deleteFile);
        } while (purged.entries() == RemovedRecordPurgeService.BATCH_SIZE);
    }

    private void deleteFile(String storedKey) {
        try {
            rawFileStorage.delete(storedKey);
        } catch (RuntimeException e) {
            log.warn("purged raw record file could not be deleted storedKey={}", storedKey, e);
        }
    }
}
