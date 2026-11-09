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

/**
 * PostgreSQL 세션 advisory lock 으로 회원마다 탈퇴 실행을 한 줄로 세운다 (DB 스키마 §3.3).
 *
 * <ul>
 *   <li><b>키</b>: bigint 키 하나짜리 {@code pg_try_advisory_lock(bigint)} 에 {@code users.id} 를 그대로 넣는다. 변환하지
 *       않아 범위가 보존된다. bigint 키 하나짜리 공간은 회원 탈퇴 전용이다 — 다른 용도는 integer 두 개짜리를 쓴다(두 공간은
 *       겹치지 않는다).</li>
 *   <li><b>연결</b>: 트랜잭션 잠금이 아니라 세션 잠금이라, 잠근 물리 연결을 처리가 끝날 때까지 빌려 둔다. Spring 트랜잭션에
 *       묶지 않고 {@link HikariDataSource} 에서 직접 빌린다. 데이터 작업은 다른 연결에서 짧은 트랜잭션으로 한다.</li>
 *   <li><b>해제</b>: 같은 연결로 {@code pg_advisory_unlock(bigint)} 을 부른 뒤 돌려준다. 해제가 {@code false} 거나 예외면 그
 *       연결은 풀에 돌려주지 않고 버린다({@code evictConnection}) — 세션이 닫히며 잠금도 풀린다. 버릴 때는 풀어내지 않은
 *       Hikari 연결 객체를 넘겨야 한다(드라이버 연결을 넘기면 Hikari 가 알아보지 못해 아무것도 버리지 않는다).</li>
 * </ul>
 *
 * <p>실행기는 스레드 하나라 같은 세션이 같은 키를 두 번 잡지 않는다. PostgreSQL 은 같은 세션의 재획득을 허용하므로 이 조건이
 * 깨지면 잠금이 줄을 세우지 못한다.
 */
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
            // 잠겼는지 모르는 세션을 풀에 돌려주지 않는다
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
