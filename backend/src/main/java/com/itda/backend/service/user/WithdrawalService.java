package com.itda.backend.service.user;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.itda.backend.exception.user.UserErrorCode;
import com.itda.backend.exception.user.UserException;
import com.itda.backend.service.organization.OrganizationHandoverAccepted;
import com.itda.backend.service.user.WithdrawalSteps.Outcome;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class WithdrawalService {

    private final WithdrawalSteps steps;
    private final WithdrawalExecutor executor;
    private final long waitMillis;
    private final boolean enabled;

    public WithdrawalService(WithdrawalSteps steps, WithdrawalExecutor executor, WithdrawalProperties properties) {
        this.steps = steps;
        this.executor = executor;
        this.waitMillis = properties.waitForCompletion().toMillis();
        this.enabled = properties.enabled();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Outcome withdraw(String principal, String expectedUserId) {
        if (!enabled) {
            throw new UserException(UserErrorCode.WITHDRAWAL_NOT_AVAILABLE);
        }
        long authenticated = UserService.parseUserId(principal);
        if (expectedUserId == null || !expectedUserId.trim().equals(String.valueOf(authenticated))) {
            throw new UserException(UserErrorCode.WITHDRAWAL_ACCOUNT_MISMATCH);
        }
        long userId = steps.accept(authenticated);
        Future<Void> running = executor.submit(userId);
        try {
            running.get(waitMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | RuntimeException e) {
            log.error("탈퇴 실행을 기다리다 오류가 났습니다: userId={}", userId, e);
        }
        return steps.outcomeOf(userId);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onHandoverAccepted(OrganizationHandoverAccepted event) {
        executor.submit(event.userId());
    }

    public void resumeAll() {
        List<Long> ids = steps.dueIds();
        ids.forEach(executor::submit);
    }
}
