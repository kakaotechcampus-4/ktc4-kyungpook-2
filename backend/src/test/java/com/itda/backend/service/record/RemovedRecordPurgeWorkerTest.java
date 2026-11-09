package com.itda.backend.service.record;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.itda.backend.service.record.RemovedRecordPurgeService.Purged;
import com.itda.backend.service.storage.RawFileStorage;

class RemovedRecordPurgeWorkerTest {

    private final RemovedRecordPurgeService purgeService = mock(RemovedRecordPurgeService.class);
    private final RawFileStorage rawFileStorage = mock(RawFileStorage.class);
    private final RemovedRecordPurgeWorker worker = new RemovedRecordPurgeWorker(purgeService, rawFileStorage);

    @Test
    void 한_번에_다_못_지우면_이어서_부르고_남은_게_적으면_멈춘다() {
        given(purgeService.purgeBatch()).willReturn(
                new Purged(RemovedRecordPurgeService.BATCH_SIZE, List.of("a.txt")),
                new Purged(3, List.of("b.txt")));

        worker.run();

        verify(purgeService, times(2)).purgeBatch();
        verify(rawFileStorage).delete("a.txt");
        verify(rawFileStorage).delete("b.txt");
    }

    @Test
    void 파일_삭제가_실패해도_나머지를_계속_지운다() {
        given(purgeService.purgeBatch()).willReturn(new Purged(2, List.of("a.txt", "b.txt")));
        willThrow(new RuntimeException("s3 down")).given(rawFileStorage).delete("a.txt");

        worker.run();

        verify(rawFileStorage).delete("b.txt");
    }

    @Test
    void 지울_것이_없으면_한_번만_본다() {
        given(purgeService.purgeBatch()).willReturn(new Purged(0, Collections.emptyList()));

        worker.run();

        verify(purgeService, times(1)).purgeBatch();
    }
}
