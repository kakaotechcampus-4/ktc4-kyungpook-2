package com.itda.backend.service.user;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class WithdrawalExecutor implements DisposableBean {

    private final WithdrawalLocks locks;
    private final WithdrawalSteps steps;
    private final KakaoUnlinkClient kakaoUnlinkClient;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "withdrawal-executor");
        thread.setDaemon(true);
        return thread;
    });

    private final ConcurrentMap<Long, Task> queued = new ConcurrentHashMap<>();

    public Future<Void> submit(long userId) {
        return queued.compute(userId, (id, existing) -> {
            if (existing != null && !existing.started) {
                return existing;
            }
            Task task = new Task(id);
            try {
                executor.execute(task::runOnce);
            } catch (RejectedExecutionException e) {
                task.cancel(false);
            }
            return task;
        });
    }

    private final class Task extends FutureTask<Void> {

        private final long userId;
        private volatile boolean started;

        private Task(long userId) {
            super(() -> process(userId), null);
            this.userId = userId;
        }

        private void runOnce() {
            started = true;
            queued.remove(userId, this);
            run();
        }
    }

    void process(long userId) {
        try {
            Optional<WithdrawalLocks.Held> lock = locks.tryAcquire(userId);
            if (lock.isEmpty()) {
                log.info("다른 실행이 이 회원의 탈퇴를 처리하고 있어 넘어갑니다: userId={}", userId);
                return;
            }
            try (WithdrawalLocks.Held held = lock.get()) {
                WithdrawalSteps.Plan plan = steps.prepare(userId);
                if (plan.kind() == WithdrawalSteps.Plan.Kind.VERIFY_REPORT) {
                    if (!held.isAlive()) {
                        log.warn("탈퇴 잠금 연결이 끊겨 카카오에 묻지 않았습니다: userId={}", userId);
                        return;
                    }
                    KakaoLinkStatus status = kakaoUnlinkClient.linkStatus(plan.kakaoUserId());
                    WithdrawalSteps.ReportResolution resolution = steps.resolveReport(userId, status, plan.reportedAt());
                    if (resolution == WithdrawalSteps.ReportResolution.RETRY_LATER) {
                        return;
                    }
                    plan = steps.prepare(userId,
                            resolution != WithdrawalSteps.ReportResolution.PROCEED_WITHOUT_REPORT);
                    if (plan.kind() == WithdrawalSteps.Plan.Kind.VERIFY_REPORT) {
                        return;
                    }
                }
                if (plan.kind() == WithdrawalSteps.Plan.Kind.NOTHING) {
                    return;
                }
                if (plan.kind() == WithdrawalSteps.Plan.Kind.CALL_KAKAO) {
                    if (!held.isAlive()) {
                        log.warn("탈퇴 잠금 연결이 끊겨 카카오를 부르지 않았습니다: userId={}", userId);
                        return;
                    }
                    KakaoUnlinkResult result = kakaoUnlinkClient.unlink(plan.kakaoUserId());
                    if (!steps.record(userId, result, plan.firstAttempt())) {
                        return;
                    }
                }
                if (steps.cleanup(userId)) {
                    log.info("탈퇴를 마쳤습니다: userId={}", userId);
                }
            }
        } catch (RuntimeException e) {
            log.error("탈퇴 처리 중 오류가 났습니다. 다시 시도합니다: userId={}", userId, e);
        }
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
