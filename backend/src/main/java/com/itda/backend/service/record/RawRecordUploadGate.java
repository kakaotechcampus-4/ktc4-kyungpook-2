package com.itda.backend.service.record;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import com.itda.backend.exception.record.RawRecordUploadBusyException;

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
