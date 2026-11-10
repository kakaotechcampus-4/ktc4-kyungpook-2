package com.itda.backend.service.user;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
