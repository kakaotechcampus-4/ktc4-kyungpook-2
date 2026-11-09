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

import lombok.extern.slf4j.Slf4j;

/**
 * 탈퇴 실행기. <b>카카오 연결 해제는 여기만 부른다.</b> 탈퇴 API 는 접수만 하고 이 실행기를 깨운다 — 사용자가 다시 시도해도
 * 카카오를 부르는 곳이 늘지 않는다.
 *
 * <p>스레드 하나다. 회원마다 아래 전체를 탈퇴 잠금({@link WithdrawalLocks}) 하나로 감싼다. 잠금을 못 잡으면 다른 실행이
 * 진행 중이니 이번에는 넘어간다. 시간이 지났다고 넘겨받지 않는다.
 * <ol>
 *   <li>상태를 다시 읽고, 카카오를 부를 것이면 호출 시각을 남기고 커밋한다 ({@link WithdrawalSteps#prepare})</li>
 *   <li>잠금이 살아 있는지 보고 카카오를 부른다</li>
 *   <li>결과를 남긴다 ({@link WithdrawalSteps#record})</li>
 *   <li>해제가 확정됐으면 DB 정리를 한다 ({@link WithdrawalSteps#cleanup})</li>
 * </ol>
 *
 * <p><b>남는 위험.</b> 실행기가 DB 연결을 잃으면 세션이 닫히며 잠금이 풀린다. 그 실행기가 살아 있는 채 멈췄다가 나중에
 * 다시 돌면, 그 사이 다른 실행이 탈퇴를 끝내고 사용자가 재가입했을 수 있다. 그러면 새 가입의 카카오 연결을 끊을 수 있다.
 * 카카오가 처리 번호를 확인해 주지 않아 막을 수 없고 시간 상한도 없다. 영향은 다음 카카오 로그인 때 동의를 다시 하는 것이다.
 */
@Slf4j
@Component
public class WithdrawalExecutor implements DisposableBean {

    private final WithdrawalLocks locks;
    private final WithdrawalSteps steps;
    private final KakaoUnlinkClient kakaoUnlinkClient;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "withdrawal-executor");
        thread.setDaemon(true);
        return thread;
    });

    /** 줄에 서 있거나 도는 중인 회원. 같은 회원을 두 번 세우지 않는다. */
    private final ConcurrentMap<Long, FutureTask<Void>> queued = new ConcurrentHashMap<>();

    public WithdrawalExecutor(WithdrawalLocks locks, WithdrawalSteps steps, KakaoUnlinkClient kakaoUnlinkClient) {
        this.locks = locks;
        this.steps = steps;
        this.kakaoUnlinkClient = kakaoUnlinkClient;
    }

    /** 이 회원의 탈퇴를 실행기 줄에 세운다. 이미 서 있으면 그 작업을 돌려준다. */
    public Future<Void> submit(long userId) {
        return queued.computeIfAbsent(userId, id -> {
            FutureTask<Void> task = new FutureTask<>(() -> process(id), null);
            try {
                executor.execute(() -> {
                    try {
                        task.run();
                    } finally {
                        queued.remove(id, task);
                    }
                });
            } catch (RejectedExecutionException e) {
                // 앱이 내려가는 중이다. 접수는 남아 있으므로 다음에 켜지면 주기 작업이 이어서 처리한다
                task.cancel(false);
            }
            return task;
        });
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
                steps.cleanup(userId);
                log.info("탈퇴를 마쳤습니다: userId={}", userId);
            }
        } catch (RuntimeException e) {
            // 접수는 남아 있다. 주기 작업이 다시 집는다
            log.error("탈퇴 처리 중 오류가 났습니다. 다시 시도합니다: userId={}", userId, e);
        }
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
