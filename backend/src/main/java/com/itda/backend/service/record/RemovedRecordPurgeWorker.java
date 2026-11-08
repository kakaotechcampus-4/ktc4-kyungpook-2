package com.itda.backend.service.record;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.itda.backend.service.record.RemovedRecordPurgeService.Purged;
import com.itda.backend.service.storage.RawFileStorage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 하루 한 번 Gate 1 에서 반려한 지 30일이 지난 데이터를 지운다 (#432). 지울 것이 한 번에 다 안 지워지면 이어서 부른다.
 *
 * <p>저장소 파일은 DB 가 커밋된 뒤에 지운다. 먼저 지웠다가 DB 가 롤백되면 행이 없는 파일을 가리키게 된다. 파일 삭제가
 * 실패하면 행 없는 파일이 남는데, 로그로만 알린다 — 원본 고아 파일 회수와 같은 운영 작업으로 치운다.
 */
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
