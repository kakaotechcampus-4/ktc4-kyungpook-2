package com.itda.backend.fixture;

import static org.assertj.core.api.Assertions.fail;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import jakarta.persistence.EntityManager;

import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

public final class LockContention {

    private final TransactionTemplate transactionTemplate;
    private final EntityManager entityManager;
    private final DataSource dataSource;

    public LockContention(TransactionTemplate transactionTemplate, EntityManager entityManager, DataSource dataSource) {
        this.transactionTemplate = transactionTemplate;
        this.entityManager = entityManager;
        this.dataSource = dataSource;
    }

    public record Held<T>(T first, MvcResult second) {
    }

    public <T> Held<T> firstHoldsThen(Callable<T> first, Callable<MvcResult> second) throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger firstPid = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<T> firstResult = executor.submit(() -> transactionTemplate.execute(status -> {
                firstPid.set(((Number) entityManager.createNativeQuery("SELECT pg_backend_pid()")
                        .getSingleResult()).intValue());
                try {
                    T value = first.call();
                    ran.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("먼저 쪽을 풀어 주지 않았습니다.");
                    }
                    return value;
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
            if (!ran.await(10, TimeUnit.SECONDS)) {
                firstResult.get(1, TimeUnit.SECONDS);
                fail("먼저 쪽이 끝나지 않았습니다.");
            }
            Future<MvcResult> secondResult = executor.submit(second);
            waitUntilBlockedBy(firstPid.get());
            release.countDown();
            T value = firstResult.get(10, TimeUnit.SECONDS);
            return new Held<>(value, secondResult.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                fail("작업 스레드가 끝나지 않았습니다.");
            }
        }
    }

    private void waitUntilBlockedBy(int holderPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (Connection observer = dataSource.getConnection();
             PreparedStatement statement = observer.prepareStatement(
                     "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))")) {
            statement.setInt(1, holderPid);
            while (System.nanoTime() < deadline) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    if (resultSet.getInt(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(50);
            }
        }
        fail("뒤의 요청이 먼저 쪽 잠금 때문에 멈추지 않았습니다. 잠금을 거치지 않고 끝났을 수 있습니다.");
    }
}
