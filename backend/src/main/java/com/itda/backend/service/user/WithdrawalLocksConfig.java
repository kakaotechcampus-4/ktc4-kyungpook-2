package com.itda.backend.service.user;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

/**
 * 탈퇴 잠금을 고른다 ({@code app.withdrawal.lock}).
 *
 * <ul>
 *   <li>{@code auto}(기본): 연결한 DB 가 PostgreSQL 이면 advisory lock, 아니면(H2 로컬 개발) 한 프로세스 안 잠금</li>
 *   <li>{@code advisory}: PostgreSQL advisory lock. 다른 DB 면 기동하지 않는다</li>
 *   <li>{@code local}: 한 프로세스 안 잠금. 여러 프로세스(배포 중 겹침)를 줄 세우지 못하므로 운영에 쓰지 않는다</li>
 * </ul>
 */
@Slf4j
@Configuration
public class WithdrawalLocksConfig {

    @Bean
    WithdrawalLocks withdrawalLocks(WithdrawalProperties properties, DataSource dataSource) {
        String mode = properties.lock() == null ? "auto" : properties.lock();
        return switch (mode) {
            case "advisory" -> new AdvisoryWithdrawalLocks(dataSource);
            case "local" -> new LocalWithdrawalLocks();
            case "auto" -> {
                if (isPostgreSql(dataSource)) {
                    yield new AdvisoryWithdrawalLocks(dataSource);
                }
                log.warn("PostgreSQL 이 아니어서 탈퇴 잠금을 한 프로세스 안 잠금으로 씁니다. 로컬 개발 전용입니다.");
                yield new LocalWithdrawalLocks();
            }
            default -> throw new IllegalStateException("app.withdrawal.lock 은 auto · advisory · local 중 하나입니다: " + mode);
        };
    }

    private static boolean isPostgreSql(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return "PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
        } catch (SQLException e) {
            throw new IllegalStateException("탈퇴 잠금을 고르려고 DB 종류를 확인하지 못했습니다.", e);
        }
    }
}
