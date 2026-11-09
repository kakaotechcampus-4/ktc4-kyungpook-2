package com.itda.backend.service.user;

import java.util.Optional;

public interface WithdrawalLocks {

    Optional<Held> tryAcquire(long userId);

    interface Held extends AutoCloseable {

        boolean isAlive();

        @Override
        void close();
    }
}
