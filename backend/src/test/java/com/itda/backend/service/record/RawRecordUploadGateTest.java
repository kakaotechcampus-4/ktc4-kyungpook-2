package com.itda.backend.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.itda.backend.exception.record.RawRecordUploadBusyException;

class RawRecordUploadGateTest {

    private static RawRecordUploadGate gate(int concurrent, int waiting, Duration maxWait) {
        return new RawRecordUploadGate(new RawRecordProperties(new RawRecordProperties.Limits(500),
                new RawRecordProperties.Upload(concurrent, waiting, maxWait)));
    }

    @Test
    void 자리가_있으면_바로_받고_돌려주면_다시_쓸_수_있다() {
        RawRecordUploadGate gate = gate(1, 0, Duration.ofSeconds(1));

        try (RawRecordUploadGate.Slot slot = gate.acquire()) {
            assertThat(slot).isNotNull();
        }
        assertThatCode(() -> gate.acquire().close()).doesNotThrowAnyException();
    }

    @Test
    void 기다리는_줄이_꽉_차면_기다리지_않고_바로_거절한다() throws Exception {
        RawRecordUploadGate gate = gate(2, 4, Duration.ofSeconds(10));
        RawRecordUploadGate.Slot held1 = gate.acquire();
        RawRecordUploadGate.Slot held2 = gate.acquire();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch started = new CountDownLatch(4);
            List<Future<?>> waiters = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                waiters.add(pool.submit(() -> {
                    started.countDown();
                    try (RawRecordUploadGate.Slot slot = gate.acquire()) {
                        Thread.sleep(10);
                    }
                    return null;
                }));
            }
            started.await(5, TimeUnit.SECONDS);
            awaitWaiting(gate, 4);

            long before = System.nanoTime();
            assertThatThrownBy(gate::acquire).isInstanceOf(RawRecordUploadBusyException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - before)).isLessThan(Duration.ofSeconds(1));

            held1.close();
            held2.close();
            for (Future<?> waiter : waiters) {
                waiter.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 기다리는_시간을_넘기면_거절한다() {
        RawRecordUploadGate gate = gate(1, 1, Duration.ofMillis(100));

        try (RawRecordUploadGate.Slot held = gate.acquire()) {
            assertThatThrownBy(gate::acquire).isInstanceOf(RawRecordUploadBusyException.class);
        }
        assertThatCode(() -> gate.acquire().close()).doesNotThrowAnyException();
    }

    @Test
    void 두_번_닫아도_자리는_한_번만_돌려준다() {
        RawRecordUploadGate gate = gate(1, 0, Duration.ofMillis(50));
        RawRecordUploadGate.Slot slot = gate.acquire();
        slot.close();
        slot.close();

        try (RawRecordUploadGate.Slot again = gate.acquire()) {
            assertThatThrownBy(gate::acquire).isInstanceOf(RawRecordUploadBusyException.class);
        }
    }

    private static void awaitWaiting(RawRecordUploadGate gate, int expected) throws InterruptedException {
        java.util.concurrent.Semaphore slots =
                (java.util.concurrent.Semaphore) org.springframework.test.util.ReflectionTestUtils.getField(gate, "slots");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (slots.getQueueLength() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(slots.getQueueLength()).isEqualTo(expected);
    }
}
