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

/**
 * 카카오 연결 해제 웹훅 수신함. 카카오는 3초 안에 {@code 200} 을 받아야 하고 다시 보내 주지 않는다.
 *
 * <ol>
 *   <li>웹훅 요청은 {@link #receive} 로 수신함에 한 줄을 남긴다. 남기는 일은 저장 스레드가 하고 요청은 {@link #RECEIVE_WAIT_MILLIS}
 *       까지만 기다린다 — DB 커넥션 풀이 바닥나 커넥션을 기다리는 시간도 응답 기한 안에 묶인다. 기다리다 먼저 답해도 저장 스레드는
 *       이어서 남긴다(카카오가 다시 보내 주지 않으므로 늦게라도 남기는 편이 낫다). 회원 행은 잠그지 않는다</li>
 *   <li>옮기는 스레드가 {@link #drain} 으로 줄을 회원 행의 알림으로 옮기고({@link WithdrawalSteps#absorbWebhook}) 실행기에
 *       세운다. 남긴 뒤마다 깨우고, 옮기지 못한 줄은 주기 작업이 다시 깨워 옮긴다. 회원 행은 기다리지 않고 잠근다 — 잠긴 회원의
 *       줄은 남겨 두고 다음 줄로 넘어간다</li>
 * </ol>
 */
@Slf4j
@Component
public class KakaoUnlinkInbox implements DisposableBean {

    /** 웹훅 요청이 저장을 기다리는 상한. 카카오의 응답 기한(3초)에서 검증 · 응답 시간을 남긴다. */
    static final long RECEIVE_WAIT_MILLIS = 2_000;

    /** 한 번에 옮기는 줄 수. */
    private static final int BATCH = 100;

    /** 저장 스레드 수와 줄 길이. 줄이 차면(오래 가는 DB 장애) 더 받지 못하고 오류 로그를 남긴다. */
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

    /** 옮기기가 줄에 서 있는지. 서 있으면 그 실행이 새 줄도 읽으므로 더 세우지 않는다. */
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

    /**
     * 받은 웹훅을 남긴다. 받은 시각은 지금으로 정해 저장 스레드에 넘기고, 남기면 옮기는 스레드를 깨운다.
     *
     * @return 기한 안에 남겼는지. false 여도 저장 스레드가 이어서 남길 수 있다(줄이 차 받지 못한 경우는 잃는다)
     */
    public boolean receive(String kakaoId) {
        KakaoUnlinkWebhook webhook = KakaoUnlinkWebhook.received(kakaoId, LocalDateTime.now(clock));
        Future<?> saving;
        try {
            saving = receiver.submit(() -> {
                transactionTemplate.executeWithoutResult(status -> repository.save(webhook));
                drainLater();
            });
        } catch (RejectedExecutionException e) {
            // 카카오는 다시 보내지 않는다. 잃은 이벤트를 찾을 수 있게 남긴다(회원번호는 남기지 않는다)
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

    /** 옮기는 스레드를 깨운다. 기다리지 않는다 — 주기 작업도 이것으로 깨워, 회원 행 잠금이 다른 탈퇴 재시도를 막지 않게 한다. */
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
            // 앱이 내려가는 중이다. 줄은 남아 있으므로 다음에 켜지면 주기 작업이 옮긴다
            requested.set(false);
        }
    }

    /** 남은 줄을 회원 행으로 옮기고 실행기에 세운다. 옮기지 못한 줄은 남겨 두고 다음에 다시 한다. */
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
        // 더 남았으면 이어서 옮긴다. 실패가 있었으면 같은 줄을 곧바로 다시 돌지 않고 주기 작업에 맡긴다
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
