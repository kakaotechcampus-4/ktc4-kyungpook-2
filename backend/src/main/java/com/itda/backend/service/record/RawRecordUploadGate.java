package com.itda.backend.service.record;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import com.itda.backend.exception.record.RawRecordUploadBusyException;

/**
 * 업로드의 바이트 읽기 · 글자 뽑기 · 나누기를 서버 전체에서 동시에 몇 개까지만 하게 묶는다 (#424).
 *
 * <p>파일마다 최대 20MB 를 힙에 올리고, docx 는 압축이 풀리며 그보다 커진다. 사전 검사({@link RawTextExtractor})가 파일 하나의
 * 크기를 묶어도 동시에 여럿이 들어오면 합이 힙(512MB)을 넘을 수 있다. 자리를 기다리는 요청 수와 시간도 묶는다 — 기다리는
 * 요청도 요청 스레드와 업로드 본문을 쥐고 있다. 넘으면 {@link RawRecordUploadBusyException}(429)이다.
 *
 * <p>저장소 전송과 DB 저장은 묶지 않는다. 바이트 배열은 자리를 돌려준 뒤에 더 쓰지 않는다.
 */
@Component
public class RawRecordUploadGate {

    private final Semaphore slots;
    private final AtomicInteger waiting = new AtomicInteger();
    private final int maxWaiting;
    private final long maxWaitNanos;

    public RawRecordUploadGate(RawRecordProperties properties) {
        RawRecordProperties.Upload upload = properties.upload();
        this.slots = new Semaphore(upload.maxConcurrent(), true);
        this.maxWaiting = upload.maxWaiting();
        this.maxWaitNanos = upload.maxWait().toNanos();
    }

    /** 자리를 하나 얻는다. try-with-resources 로 돌려준다. */
    public Slot acquire() {
        if (slots.tryAcquire()) {
            return new Slot(slots);
        }
        if (waiting.incrementAndGet() > maxWaiting) {
            waiting.decrementAndGet();
            throw new RawRecordUploadBusyException("upload slots and waiting queue are full");
        }
        try {
            if (!slots.tryAcquire(maxWaitNanos, TimeUnit.NANOSECONDS)) {
                throw new RawRecordUploadBusyException("timed out waiting for an upload slot");
            }
            return new Slot(slots);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RawRecordUploadBusyException("interrupted while waiting for an upload slot");
        } finally {
            waiting.decrementAndGet();
        }
    }

    /** 얻은 자리. 두 번 닫아도 한 번만 돌려준다. */
    public static final class Slot implements AutoCloseable {

        private final Semaphore slots;
        private boolean released;

        private Slot(Semaphore slots) {
            this.slots = slots;
        }

        @Override
        public void close() {
            if (!released) {
                released = true;
                slots.release();
            }
        }
    }
}
