package com.itda.backend.service.user;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

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
