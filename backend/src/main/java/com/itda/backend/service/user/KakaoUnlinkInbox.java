package com.itda.backend.service.user;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.itda.backend.domain.user.KakaoUnlinkWebhook;
import com.itda.backend.repository.user.KakaoUnlinkWebhookRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class KakaoUnlinkInbox implements DisposableBean {

    static final long RECEIVE_WAIT_MILLIS = 2_000;

    private static final int BATCH = 100;

    private static final int RECEIVERS = 2;
    private static final int RECEIVE_QUEUE = 200;

    private final KakaoUnlinkWebhookRepository repository;
    private final WithdrawalSteps steps;
    private final WithdrawalExecutor executor;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    private final ExecutorService receiver = new ThreadPoolExecutor(
            RECEIVERS, RECEIVERS, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(RECEIVE_QUEUE),
            daemon("kakao-unlink-receive"));
    private final ExecutorService worker = Executors.newSingleThreadExecutor(daemon("kakao-unlink-inbox"));

    private final AtomicBoolean requested = new AtomicBoolean();

    public KakaoUnlinkInbox(
            KakaoUnlinkWebhookRepository repository,
            WithdrawalSteps steps,
            WithdrawalExecutor executor,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.repository = repository;
        this.steps = steps;
        this.executor = executor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public boolean receive(String kakaoId) {
        KakaoUnlinkWebhook webhook = KakaoUnlinkWebhook.received(kakaoId, LocalDateTime.now(clock));
        Future<?> saving;
        try {
            saving = receiver.submit(() -> {
                transactionTemplate.executeWithoutResult(status -> repository.save(webhook));
                drainLater();
            });
        } catch (RejectedExecutionException e) {
            log.error("연결 해제 웹훅 저장 줄이 가득 차 이 웹훅을 남기지 못했습니다.");
            return false;
        }
        try {
            saving.get(RECEIVE_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            log.warn("연결 해제 웹훅을 기한 안에 남기지 못해 먼저 답합니다. 저장은 이어서 합니다.");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            log.error("연결 해제 웹훅을 남기지 못했습니다.", e.getCause());
            return false;
        }
    }

    public void drainLater() {
        if (requested.getAndSet(true)) {
            return;
        }
        try {
            worker.execute(() -> {
                requested.set(false);
                drain();
            });
        } catch (RejectedExecutionException e) {
            requested.set(false);
        }
    }

    public void drain() {
        List<Long> ids = repository.findPendingIds(PageRequest.of(0, BATCH));
        boolean failed = false;
        for (Long id : ids) {
            try {
                steps.absorbWebhook(id).ifPresent(executor::submit);
            } catch (PessimisticLockingFailureException e) {
                failed = true;
                log.info("회원 행이 잠겨 있어 연결 해제 웹훅을 다음에 옮깁니다: webhookId={}", id);
            } catch (RuntimeException e) {
                failed = true;
                log.error("연결 해제 웹훅을 회원에 옮기지 못했습니다. 다음에 다시 합니다: webhookId={}", id, e);
            }
        }
        if (ids.size() == BATCH && !failed) {
            drainLater();
        }
    }

    @Override
    public void destroy() {
        receiver.shutdownNow();
        worker.shutdownNow();
    }

    private static ThreadFactory daemon(String name) {
        AtomicInteger count = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
