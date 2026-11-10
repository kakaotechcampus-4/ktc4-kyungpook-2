package com.itda.backend.service.insight;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.UnexpectedRollbackException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.itda.backend.domain.insight.InsightRun;
import com.itda.backend.repository.insight.InsightRunRepository;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class InsightRunClaimConcurrencyIntegrationTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 5);
    private static final LocalDate TO = LocalDate.of(2026, 10, 11);
    private static final Long CHILD = 8L;

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("itda_insight_claim_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired
    private InsightResultRecorder recorder;
    @Autowired
    private InsightRunRepository insightRunRepository;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        insightRunRepository.deleteAll();
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void 둘이_동시에_집으면_하나만_남는다() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        Callable<Object> attempt = () -> {
            start.await(5, TimeUnit.SECONDS);
            try {
                return recorder.claim(CHILD, FROM, TO);
            } catch (DataIntegrityViolationException | UnexpectedRollbackException e) {
                return e;
            }
        };

        List<Future<Object>> futures = new ArrayList<>();
        futures.add(pool.submit(attempt));
        futures.add(pool.submit(attempt));

        List<Object> outcomes = new ArrayList<>();
        for (Future<Object> future : futures) {
            outcomes.add(future.get(10, TimeUnit.SECONDS));
        }

        long won = outcomes.stream()
                .filter(Optional.class::isInstance)
                .map(Optional.class::cast)
                .filter(Optional::isPresent)
                .count();
        assertThat(won).as("집기에 성공한 쪽").isEqualTo(1);
        assertThat(insightRunRepository.findAll()).hasSize(1);
    }

    @Test
    void 진_쪽이_받는_것은_빈_값이_아니라_예외다() throws Exception {
        recorder.claim(CHILD, FROM, TO).orElseThrow();
        assertThat(catchClaim(InsightRun.claim(CHILD, FROM, TO, 1)))
                .isInstanceOfAny(DataIntegrityViolationException.class, UnexpectedRollbackException.class);
    }

    private Throwable catchClaim(InsightRun duplicate) {
        try {
            insightRunRepository.saveAndFlush(duplicate);
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }
}
