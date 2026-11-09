package com.itda.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 자주 읽는 테이블의 인덱스(#479)가 엔티티 선언대로 만들어지는지 실제 PostgreSQL 에서 확인한다.
 *
 * <p>운영 DB 에는 같은 이름 · 같은 정의로 {@code CREATE INDEX CONCURRENTLY} 를 먼저 실행한다(DB 스키마 §11.4). Hibernate 는
 * 이름만 보고 이미 있으면 건너뛰므로, 엔티티의 이름이나 컬럼 순서가 그 SQL 과 어긋나면 운영에는 다른 인덱스가 남는다.
 * 그래서 이름 · 테이블 · 컬럼 순서 · btree · 비유니크 · 조건 없음까지 운영 검증 쿼리와 같은 문장으로 비교한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class QueryIndexIntegrationTest {

    /** 인덱스 이름 → {테이블, 컬럼}. 운영에서 실행하는 SQL · 검증 쿼리(DB 스키마 §11.4)와 같아야 한다. */
    private static final Map<String, List<String>> EXPECTED = new LinkedHashMap<>();

    static {
        EXPECTED.put("idx_journal_entry_status_id", List.of("journal_entry", "status, id"));
        EXPECTED.put("idx_journal_entry_raw_record_id", List.of("journal_entry", "raw_record_id"));
        EXPECTED.put("idx_journal_entry_summary_id", List.of("journal_entry", "summary_id"));
        EXPECTED.put("idx_journal_entry_child_id_entry_date", List.of("journal_entry", "child_id, entry_date"));
        EXPECTED.put("idx_raw_record_institution_id", List.of("raw_record", "institution_id"));
        EXPECTED.put("idx_matching_result_journal_entry_id", List.of("matching_result", "journal_entry_id"));
        EXPECTED.put("idx_validation_result_journal_entry_id", List.of("validation_result", "journal_entry_id"));
        EXPECTED.put("idx_summary_result_institution_id_status", List.of("summary_result", "institution_id, status"));
        EXPECTED.put("idx_insight_result_child_id", List.of("insight_result", "child_id"));
        EXPECTED.put("idx_child_guardian_user_id", List.of("child_guardian", "user_id"));
        EXPECTED.put("idx_sharing_history_receiver_type_receiver_id",
                List.of("sharing_history", "receiver_type, receiver_id"));
    }

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("itda_index_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired private JdbcTemplate jdbc;

    @Test
    void entityIndexesMatchTheSqlRunOnTheDeployedDatabase() {
        Map<String, String> actual = new LinkedHashMap<>();
        jdbc.query("""
                select c.relname, pg_get_indexdef(c.oid)
                from pg_class c join pg_index x on x.indexrelid = c.oid
                where c.relkind = 'i' and c.relnamespace = 'public'::regnamespace
                  and c.relname = any (?) and x.indisvalid and x.indisready
                """,
                rs -> {
                    actual.put(rs.getString(1), rs.getString(2));
                },
                (Object) EXPECTED.keySet().toArray(String[]::new));

        Map<String, String> expected = new LinkedHashMap<>();
        EXPECTED.forEach((name, tableAndColumns) -> expected.put(name, "CREATE INDEX %s ON public.%s USING btree (%s)"
                .formatted(name, tableAndColumns.get(0), tableAndColumns.get(1))));
        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
    }
}
