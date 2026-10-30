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

/**
 * 두 작업이 같은 행 잠금에서 줄을 서는지 PostgreSQL 에서 재현한다. 잠금 동시성 통합 테스트들이 함께 쓴다.
 *
 * <p>먼저 가는 쪽은 바깥 트랜잭션 안에서 실제 서비스를 불러 <b>커밋하지 않고 잠금을 쥔 채</b> 멈춘다 — 서비스 트랜잭션이
 * 바깥에 합류한다. 뒤에 오는 쪽은 MockMvc 로 보내 보안 필터부터 거친다. 뒤의 요청이 먼저 쪽 때문에 멈췄는지
 * {@code pg_blocking_pids} 로 확인한 뒤 먼저 쪽을 커밋한다. 잠금을 거치지 않고 끝나면 실패한다.
 */
public final class LockContention {

    private final TransactionTemplate transactionTemplate;
    private final EntityManager entityManager;
    private final DataSource dataSource;

    public LockContention(TransactionTemplate transactionTemplate, EntityManager entityManager, DataSource dataSource) {
        this.transactionTemplate = transactionTemplate;
        this.entityManager = entityManager;
        this.dataSource = dataSource;
    }

    /** 먼저 쪽의 반환값과 뒤 쪽의 HTTP 응답. */
    public record Held<T>(T first, MvcResult second) {
    }

    /**
     * {@code first} 를 바깥 트랜잭션 안에서 끝내고 커밋하지 않은 채 잡아 둔다. 그동안 {@code second} 를 넣고, 그것이
     * {@code first} 의 잠금 때문에 멈췄는지 확인한 뒤 커밋한다.
     */
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

    /** 먼저 쪽 세션 때문에 막힌 세션이 생길 때까지 기다린다. 잠금을 거치지 않고 끝났으면 실패한다. */
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
