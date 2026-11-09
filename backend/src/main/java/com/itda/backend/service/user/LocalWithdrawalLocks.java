package com.itda.backend.service.user;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 한 프로세스 안에서만 줄을 세우는 탈퇴 잠금. H2(테스트 · 로컬 개발) 전용이다 — H2 에는 advisory lock 함수가 없다.
 * 운영(PostgreSQL)은 {@link AdvisoryWithdrawalLocks} 다. 고르는 곳은 {@link WithdrawalLocksConfig}.
 */
public class LocalWithdrawalLocks implements WithdrawalLocks {

    private final Set<Long> held = ConcurrentHashMap.newKeySet();

    @Override
    public Optional<Held> tryAcquire(long userId) {
        if (!held.add(userId)) {
            return Optional.empty();
        }
        return Optional.of(new Held() {
            @Override
            public boolean isAlive() {
                return true;
            }

            @Override
            public void close() {
                held.remove(userId);
            }
        });
    }
}
