package com.itda.backend.service.child;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.BDDMockito.given;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
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

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganizationRequest;
import com.itda.backend.domain.child.ChildOrganizationRequestStatus;
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.user.User;
import com.itda.backend.dto.response.organization.MyInstitutionResponse;
import com.itda.backend.exception.child.ChildException;
import com.itda.backend.exception.child.InstitutionLinkException;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildOrganizationRequestRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.invitation.ChildGuardianRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.user.UserRepository;
import com.itda.backend.service.invitation.ChildGuardianService;
import com.itda.backend.service.organization.OrganizationService;
import com.itda.backend.service.user.UserService;

/**
 * 기관 연결 요청·수락·거절, 보호자 연결 해제, 기관 코드 발급이 겹칠 때를 PostgreSQL 에서 재현한다.
 *
 * <p>다른 커넥션(홀더)이 아이 행이나 기관 행을 잠근 채 잡아 둔다. 요청들이 홀더 때문에 멈췄는지
 * {@code pg_blocking_pids} 로 확인한 뒤 풀어서, 잠금을 기다린 다음 최신 상태로 판단하는지 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class InstitutionLinkConcurrencyIntegrationTest {

    private static final String MOTHER = "u-mother";
    private static final String STAFF = "u-staff";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("itda_institution_link_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired private InstitutionLinkService institutionLinkService;
    @Autowired private ChildGuardianService childGuardianService;
    @Autowired private OrganizationService organizationService;
    @Autowired private ChildRepository childRepository;
    @Autowired private ChildOrganizationRepository childOrganizationRepository;
    @Autowired private ChildOrganizationRequestRepository requestRepository;
    @Autowired private ChildGuardianRepository childGuardianRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DataSource dataSource;

    @MockitoBean private UserService userService;

    private Organization center;
    private Child child;
    private String centerCode;

    @BeforeEach
    void setUp() {
        center = OrganizationFixture.center();
        center.assignCode("CENTERAB");
        center = organizationRepository.save(center);
        centerCode = center.getCode();
        child = childRepository.save(Child.of("임유진", LocalDate.of(2017, 3, 14)));
        User mother = userRepository.save(UserFixture.parent("kakao-mother", "엄마"));
        User staff = userRepository.save(UserFixture.organizationUser("kakao-staff", "센터선생", center.getId()));
        // 초대 코드 경로는 이 테스트의 관심이 아니다. 연결 행은 초대 id 만 있으면 된다.
        childGuardianRepository.save(ChildGuardian.link(child.getId(), mother.getId(), 1L));
        given(userService.getParentIdOf(MOTHER)).willReturn(mother.getId());
        given(userService.getOrganizationIdOf(STAFF)).willReturn(center.getId());
        given(userService.getUserIdOf(STAFF)).willReturn(staff.getId());
    }

    @AfterEach
    void cleanUp() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM child_organization_request");
            statement.executeUpdate("DELETE FROM sharing_consent");
            statement.executeUpdate("DELETE FROM child_guardian");
            statement.executeUpdate("DELETE FROM child_organization");
            statement.executeUpdate("DELETE FROM child");
            statement.executeUpdate("DELETE FROM users");
            statement.executeUpdate("DELETE FROM organization");
        }
    }

    private String lockChild() {
        return "SELECT id FROM child WHERE id = " + child.getId() + " FOR UPDATE";
    }

    @Test
    void 같은_보호자가_동시에_두_번_요청해도_대기_행은_하나다() throws Exception {
        List<String> outcomes = whileHeld(lockChild(),
                () -> institutionLinkService.request(MOTHER, child.getId(), centerCode),
                () -> institutionLinkService.request(MOTHER, child.getId(), centerCode));

        assertThat(outcomes).containsExactly("OK", "OK");
        assertThat(requestRepository.findAll()).singleElement().matches(ChildOrganizationRequest::isPending);
    }

    /** 연결 해제가 먼저 잠금을 얻으면 요청이 거둬진다. 뒤이은 수락은 잠근 뒤 다시 읽으므로 옛 PENDING 을 보지 않는다. */
    @Test
    void 연결_해제가_먼저_끝나면_수락은_실패하고_명부에_오르지_않는다() throws Exception {
        institutionLinkService.request(MOTHER, child.getId(), centerCode);

        List<String> outcomes = whileHeld(lockChild(),
                () -> {
                    childGuardianService.unlinkBySelf(MOTHER, child.getId());
                    return null;
                },
                () -> institutionLinkService.accept(STAFF, child.getId(), "C-1"));

        assertThat(outcomes).containsExactly("OK", "LINK_REQUEST_NOT_FOUND");
        assertThat(requestRepository.findAll()).singleElement()
                .extracting(ChildOrganizationRequest::getStatus).isEqualTo(ChildOrganizationRequestStatus.CANCELLED);
        assertThat(childOrganizationRepository.findByChildIdAndOrganizationId(child.getId(), center.getId())).isEmpty();
    }

    @Test
    void 수락과_거절이_겹치면_한쪽만_성공한다() throws Exception {
        institutionLinkService.request(MOTHER, child.getId(), centerCode);

        List<String> outcomes = whileHeld(lockChild(),
                () -> institutionLinkService.accept(STAFF, child.getId(), "C-1"),
                () -> {
                    institutionLinkService.reject(STAFF, child.getId());
                    return null;
                });

        assertThat(outcomes).containsExactly("OK", "LINK_REQUEST_NOT_FOUND");
        assertThat(requestRepository.findAll()).singleElement()
                .extracting(ChildOrganizationRequest::getStatus).isEqualTo(ChildOrganizationRequestStatus.ACCEPTED);
        assertThat(childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(
                child.getId(), center.getId())).isTrue();
    }

    /** 코드가 없던 기관을 두 화면이 동시에 열어도 코드는 한 번만 붙는다. 잠근 뒤 비어 있는지 다시 보기 때문이다. */
    @Test
    void 코드가_없던_기관을_동시에_열어도_같은_코드가_나온다() throws Exception {
        Organization fresh = organizationRepository.save(OrganizationFixture.center());
        given(userService.getOrganizationIdOf("u-fresh")).willReturn(fresh.getId());
        List<MyInstitutionResponse> responses = java.util.Collections.synchronizedList(new ArrayList<>());

        List<String> outcomes = whileHeld("SELECT id FROM organization WHERE id = " + fresh.getId() + " FOR UPDATE",
                () -> responses.add(organizationService.getMine("u-fresh")),
                () -> responses.add(organizationService.getMine("u-fresh")));

        assertThat(outcomes).containsExactly("OK", "OK");
        assertThat(responses).extracting(MyInstitutionResponse::code).doesNotContainNull().containsOnly(
                organizationRepository.findById(fresh.getId()).orElseThrow().getCode());
    }

    /**
     * 홀더가 행을 잠근 채로, 작업을 하나씩 넣고 홀더 때문에 줄을 섰는지 확인한 뒤 다음 작업을 넣는다.
     * PostgreSQL 은 같은 행을 기다리는 세션에게 줄 선 순서대로 잠금을 주므로 결과도 넣은 순서로 돌려준다.
     */
    @SafeVarargs
    private List<String> whileHeld(String holderSql, Callable<?>... tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.length);
        List<Future<?>> futures = new ArrayList<>();
        boolean released = false;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                int holderPid = backendPid(holder);
                try (Statement statement = holder.createStatement()) {
                    statement.execute(holderSql);
                }
                for (int i = 0; i < tasks.length; i++) {
                    futures.add(executor.submit(tasks[i]));
                    waitUntilBlocked(holderPid, i + 1);
                }
                holder.rollback();
                released = true;

                List<String> outcomes = new ArrayList<>();
                for (Future<?> future : futures) {
                    outcomes.add(outcomeOf(future));
                }
                return outcomes;
            } finally {
                if (!released) {
                    holder.rollback();
                }
                futures.forEach(future -> future.cancel(true));
                executor.shutdownNow();
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    fail("작업 스레드가 끝나지 않았습니다.");
                }
            }
        }
    }

    private static String outcomeOf(Future<?> future) throws Exception {
        try {
            future.get(10, TimeUnit.SECONDS);
            return "OK";
        } catch (ExecutionException e) {
            if (e.getCause() instanceof InstitutionLinkException linkException) {
                return linkException.getErrorCode().name();
            }
            if (e.getCause() instanceof ChildException childException) {
                return childException.getErrorCode().name();
            }
            throw e;
        }
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_backend_pid()");
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    /** 홀더 때문에 막힌 세션이 있고, 잠금을 기다리는 세션이 {@code expected} 개가 될 때까지 기다린다. */
    private void waitUntilBlocked(int holderPid, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (Connection observer = dataSource.getConnection();
             PreparedStatement statement = observer.prepareStatement(
                     "SELECT count(*) FILTER (WHERE ? = ANY(pg_blocking_pids(pid))), "
                             + "count(*) FILTER (WHERE cardinality(pg_blocking_pids(pid)) > 0) "
                             + "FROM pg_stat_activity")) {
            statement.setInt(1, holderPid);
            while (System.nanoTime() < deadline) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    if (resultSet.getInt(1) > 0 && resultSet.getInt(2) >= expected) {
                        return;
                    }
                }
                Thread.sleep(50);
            }
        }
        fail("작업이 홀더 때문에 멈추지 않았습니다. 잠금을 거치지 않고 끝났을 수 있습니다.");
    }
}
