package com.itda.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.BDDMockito.given;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.itda.backend.domain.Child;
import com.itda.backend.domain.Organization;
import com.itda.backend.dto.request.RegisterChildRequest;
import com.itda.backend.exception.ChildErrorCode;
import com.itda.backend.exception.ChildException;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.repository.ChildOrganizationRepository;
import com.itda.backend.repository.ChildRepository;
import com.itda.backend.repository.OrganizationRepository;

/**
 * 사전 조회를 통과한 뒤 실제 DB 유니크 제약에 걸리는 경로를 PostgreSQL 에서 재현한다.
 *
 * <p>다른 커넥션이 같은 관리번호의 연결 행을 넣고 커밋하지 않은 채 잡아 둔다. READ COMMITTED 라 등록 쪽의
 * 사전 조회는 그 행을 보지 못하고 통과하고, 연결 INSERT 에서 유니크 인덱스를 기다리며 멈춘다. 잡아 둔 쪽이
 * 커밋하면 등록 쪽 INSERT 가 유니크 위반으로 실패한다. 그 예외가 409 로 바뀌고, 먼저 저장한 아동도
 * 롤백되는지 본다.
 *
 * <p>잡아 둔 쪽을 커밋하기 전에 등록 세션이 "잡아 둔 세션 때문에" 멈췄는지 {@code pg_blocking_pids} 로 확인한다.
 * 그래야 등록 쪽이 사전 조회를 이미 지났다는 것이 보장된다. 그렇지 않으면 사전 조회 경로의 409 로
 * 우연히 통과할 수 있다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class ChildServiceExternalIdConflictIntegrationTest {

    private static final String USER_ID = "u-conflict";
    private static final String EXTERNAL_ID = "2026-0031";
    private static final String HOLDER_CHILD_NAME = "점유검증아동";
    private static final String REGISTERING_CHILD_NAME = "충돌검증아동";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("itda_child_conflict_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired private ChildService childService;
    @Autowired private ChildRepository childRepository;
    @Autowired private ChildOrganizationRepository childOrganizationRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private DataSource dataSource;

    @MockitoBean private UserService userService;

    private Organization organization;

    @BeforeEach
    void setUp() {
        organization = organizationRepository.save(OrganizationFixture.center());
        given(userService.getOrganizationIdOf(USER_ID)).willReturn(organization.getId());
    }

    @AfterEach
    void cleanUp() {
        childOrganizationRepository.deleteAll(
                childOrganizationRepository.findByOrganizationIdAndDeletedAtIsNull(organization.getId()));
        childRepository.deleteAll(childrenNamed(HOLDER_CHILD_NAME));
        childRepository.deleteAll(childrenNamed(REGISTERING_CHILD_NAME));
        organizationRepository.delete(organization);
    }

    @Test
    void 사전_조회를_통과한_동시_등록도_409이고_아동은_롤백된다() throws Exception {
        Child holderChild = childRepository.save(Child.of(HOLDER_CHILD_NAME, LocalDate.of(2018, 5, 1)));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> registering = null;
        boolean holderCommitted = false;

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                int holderPid = backendPid(holder);
                insertUncommittedLink(holder, holderChild.getId());

                registering = executor.submit(() -> childService.register(
                        USER_ID, new RegisterChildRequest(REGISTERING_CHILD_NAME, "2017-03-14", EXTERNAL_ID)));

                waitUntilBlockedBy(holderPid);
                holder.commit();
                holderCommitted = true;

                Future<?> result = registering;
                assertThatThrownBy(() -> result.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .cause()
                        .isInstanceOfSatisfying(ChildException.class,
                                e -> assertThat(e.getErrorCode()).isEqualTo(ChildErrorCode.DUPLICATE_EXTERNAL_ID));
                assertThat(childrenNamed(REGISTERING_CHILD_NAME)).isEmpty();
            } finally {
                // 실패해도 이 순서로 정리한다: 잠금을 풀어 등록 스레드를 빼내고 → 스레드를 끝내고 → 커넥션을 닫는다.
                if (!holderCommitted) {
                    holder.rollback();
                }
                if (registering != null) {
                    registering.cancel(true);
                }
                executor.shutdownNow();
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    fail("등록 스레드가 끝나지 않았습니다.");
                }
            }
        }
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_backend_pid()");
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private void insertUncommittedLink(Connection holder, Long childId) throws SQLException {
        try (PreparedStatement statement = holder.prepareStatement(
                "INSERT INTO child_organization (child_id, organization_id, external_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, now(), now())")) {
            statement.setLong(1, childId);
            statement.setLong(2, organization.getId());
            statement.setString(3, EXTERNAL_ID);
            statement.executeUpdate();
        }
    }

    /** 잡아 둔 세션 때문에 막힌 세션이 생길 때까지 기다린다. 다른 이유로 기다리는 세션은 세지 않는다. */
    private void waitUntilBlockedBy(int holderPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (Connection observer = dataSource.getConnection();
             PreparedStatement statement = observer.prepareStatement(
                     "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))")) {
            statement.setInt(1, holderPid);
            while (System.nanoTime() < deadline) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    if (resultSet.getInt(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(50);
            }
        }
        fail("등록 세션이 잡아 둔 세션 때문에 멈추지 않았습니다. 사전 조회 경로로 끝났을 수 있습니다.");
    }

    private List<Child> childrenNamed(String name) {
        return childRepository.findAll().stream()
                .filter(child -> name.equals(child.getName()))
                .toList();
    }
}
