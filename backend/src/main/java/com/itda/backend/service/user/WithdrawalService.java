package com.itda.backend.service.user;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.stereotype.Service;

import com.itda.backend.service.user.WithdrawalSteps.Outcome;

import lombok.extern.slf4j.Slf4j;

/**
 * 회원 탈퇴 (DELETE /api/v1/auth/me). 보호자와 가입을 마치지 않은 사람만 탈퇴할 수 있다.
 *
 * <p>접수를 커밋한 뒤 실행기를 깨우고 잠깐 기다린다. 그 안에 {@code deleted_at} 까지 확인되면 완료, 접수가 취소됐으면 취소,
 * 아니면 처리 중이다 — 처리 중이면 실행기 주기 작업이 이어서 끝낸다. 요청을 받은 이 코드는 카카오를 직접 부르지 않는다.
 *
 * <p>트랜잭션을 걸지 않는다. 접수는 실행기가 읽기 전에 커밋돼 있어야 한다.
 */
@Slf4j
@Service
public class WithdrawalService {

    private final WithdrawalSteps steps;
    private final WithdrawalExecutor executor;
    private final long waitMillis;

    public WithdrawalService(WithdrawalSteps steps, WithdrawalExecutor executor, WithdrawalProperties properties) {
        this.steps = steps;
        this.executor = executor;
        this.waitMillis = properties.waitForCompletion().toMillis();
    }

    public Outcome withdraw(String principal) {
        long userId = steps.accept(UserService.parseUserId(principal));
        Future<Void> running = executor.submit(userId);
        try {
            running.get(waitMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 처리 중이다. 실행기가 이어서 끝낸다
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | RuntimeException e) {
            log.error("탈퇴 실행을 기다리다 오류가 났습니다: userId={}", userId, e);
        }
        return steps.outcomeOf(userId);
    }

    /** 주기 작업이 진행 중인 탈퇴를 모두 실행기 줄에 다시 세운다. */
    public void resumeAll() {
        List<Long> ids = steps.withdrawingIds();
        ids.forEach(executor::submit);
    }
}
