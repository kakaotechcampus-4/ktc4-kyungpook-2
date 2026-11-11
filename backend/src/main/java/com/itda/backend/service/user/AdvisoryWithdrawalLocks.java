package com.itda.backend.service.user;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.dao.DataAccessResourceFailureException;

import com.zaxxer.hikari.HikariDataSource;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AdvisoryWithdrawalLocks implements WithdrawalLocks {

    private final HikariDataSource dataSource;

    public AdvisoryWithdrawalLocks(DataSource dataSource) {
        try {
            this.dataSource = dataSource.unwrap(HikariDataSource.class);
        } catch (SQLException e) {
            throw new IllegalStateException("탈퇴 잠금은 HikariCP 연결 풀이 필요합니다.", e);
        }
    }

    @Override
    public Optional<Held> tryAcquire(long userId) {
        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException e) {
            throw new DataAccessResourceFailureException("탈퇴 잠금 연결을 빌리지 못했습니다.", e);
        }
        boolean acquired;
        try {
            acquired = queryBoolean(connection, "SELECT pg_try_advisory_lock(?)", userId);
        } catch (SQLException | RuntimeException e) {
            discard(connection, userId);
            throw new DataAccessResourceFailureException("탈퇴 잠금을 잡지 못했습니다.", e);
        }
        if (!acquired) {
            closeQuietly(connection);
            return Optional.empty();
        }
        return Optional.of(new SessionLock(connection, userId));
    }

    private static boolean queryBoolean(Connection connection, String sql, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getBoolean(1);
            }
        }
    }

    private void discard(Connection connection, long userId) {
        try {
            dataSource.evictConnection(connection);
        } catch (RuntimeException e) {
            log.error("탈퇴 잠금 연결을 버리지 못했습니다: userId={}", userId, e);
        }
        closeQuietly(connection);
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException | RuntimeException e) {
            log.warn("탈퇴 잠금 연결을 닫지 못했습니다.", e);
        }
    }

    private final class SessionLock implements Held {

        private final Connection connection;
        private final long userId;

        private SessionLock(Connection connection, long userId) {
            this.connection = connection;
            this.userId = userId;
        }

        @Override
        public boolean isAlive() {
            try {
                return connection.isValid(2);
            } catch (SQLException e) {
                return false;
            }
        }

        @Override
        public void close() {
            boolean released = false;
            try {
                released = queryBoolean(connection, "SELECT pg_advisory_unlock(?)", userId);
                if (!released) {
                    log.error("탈퇴 잠금이 이 세션에 없었습니다. 연결을 버립니다: userId={}", userId);
                }
            } catch (SQLException | RuntimeException e) {
                log.error("탈퇴 잠금을 풀지 못했습니다. 연결을 버립니다: userId={}", userId, e);
            }
            if (released) {
                closeQuietly(connection);
            } else {
                discard(connection, userId);
            }
        }
    }
}
