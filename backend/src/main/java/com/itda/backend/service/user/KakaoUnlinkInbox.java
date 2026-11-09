package com.itda.backend.service.user;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.user.KakaoUnlinkWebhook;
import com.itda.backend.repository.user.KakaoUnlinkWebhookRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 카카오 연결 해제 웹훅 수신함. 카카오는 3초 안에 {@code 200} 을 받아야 하고 다시 보내 주지 않는다.
 *
 * <ol>
 *   <li>웹훅 요청은 {@link #receive} 로 수신함에 한 줄만 남기고 답한다 — 회원 행을 잠그지 않아, 다른 트랜잭션이 그 행을 오래
 *       잠가도 기다리지 않는다</li>
 *   <li>옮기는 스레드가 {@link #drain} 으로 줄을 회원 행의 알림으로 옮기고({@link WithdrawalSteps#absorbWebhook}) 실행기에
 *       세운다. 웹훅을 받을 때마다 깨우고, 옮기지 못한 줄은 주기 작업이 다시 옮긴다</li>
 * </ol>
 */
@Slf4j
@Component
public class KakaoUnlinkInbox implements DisposableBean {

    /** 수신함에 남기는 트랜잭션의 상한(초). 카카오의 응답 기한(3초) 안에 답하게 한다. */
    static final int RECEIVE_TIMEOUT_SECONDS = 2;

    /** 한 번에 옮기는 줄 수. */
    private static final int BATCH = 100;

    private final KakaoUnlinkWebhookRepository repository;
    private final WithdrawalSteps steps;
    private final WithdrawalExecutor executor;
    private final Clock clock;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kakao-unlink-inbox");
        thread.setDaemon(true);
        return thread;
    });

    /** 옮기기가 줄에 서 있는지. 서 있으면 그 실행이 새 줄도 읽으므로 더 세우지 않는다. */
    private final AtomicBoolean requested = new AtomicBoolean();

    public KakaoUnlinkInbox(
            KakaoUnlinkWebhookRepository repository, WithdrawalSteps steps, WithdrawalExecutor executor, Clock clock) {
        this.repository = repository;
        this.steps = steps;
        this.executor = executor;
        this.clock = clock;
    }

    /** 받은 웹훅을 남긴다. 새 줄을 넣기만 해 회원 행 잠금을 기다리지 않는다. */
    @Transactional(timeout = RECEIVE_TIMEOUT_SECONDS)
    public void receive(String kakaoId) {
        repository.save(KakaoUnlinkWebhook.received(kakaoId, LocalDateTime.now(clock)));
    }

    /** 옮기는 스레드를 깨운다. 받은 트랜잭션이 커밋된 뒤에 부른다. */
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
        worker.shutdownNow();
    }
}
