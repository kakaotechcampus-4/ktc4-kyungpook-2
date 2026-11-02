package com.itda.backend.service.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.BDDMockito.given;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.record.RawRecordStatus;
import com.itda.backend.domain.summary.SummaryRunRequest;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.record.RawRecordRepository;
import com.itda.backend.repository.summary.SummaryRunRequestRepository;
import com.itda.backend.service.user.UserService;

/**
 * 같은 묶음을 처음 두 번 동시에 누를 때를 PostgreSQL 에서 확인한다 (#181).
 *
 * <p>다른 커넥션(홀더)이 같은 묶음의 요청 행을 넣고 커밋하지 않고 잡아 둔다. [요약] 은 행을 못 봐서 새로 넣다가
 * UNIQUE 인덱스에서 홀더를 기다린다. 홀더가 커밋하면 UNIQUE 위반이 나고, 새 트랜잭션에서 다시 시도해 먼저 들어간
 * 행의 누른 시각만 바꿔야 한다. 제약 이름을 PostgreSQL 오류에서 꺼내는지도 여기서만 확인된다 (H2 와 다르다).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class SummaryRunServiceIntegrationTest {

    private static final Long ORG = 3L;
    private static final Long CHILD = 8L;
    private static final String TEACHER = "99";
    private static final LocalDate DATE = LocalDate.of(2026, 10, 8);

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("itda_summary_run_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired private SummaryRunService summaryRunService;
    @Autowired private SummaryRunRequestRepository summaryRunRequestRepository;
    @Autowired private ChildOrganizationRepository childOrganizationRepository;
    @Autowired private RawRecordRepository rawRecordRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private DataSource dataSource;

    @MockitoBean private UserService userService;

    @BeforeEach
    void setUp() {
        given(userService.getOrganizationIdOf(TEACHER)).willReturn(ORG);
        childOrganizationRepository.save(ChildOrganization.of(CHILD, ORG));
        Long rawRecordId = rawRecordRepository.save(new RawRecord(String.valueOf(ORG), "school.pdf",
                "kept/school.pdf", "application/pdf", 12, RawRecordStatus.PENDING)).getId();
        JournalEntry entry = JournalEntry.of(rawRecordId, DATE, "블록 놀이를 했다.", 1);
        entry.startMatching();
        entry.confirmMatch(CHILD);
        entry.startValidating();
        entry.passValidation();
        journalEntryRepository.save(entry);
    }

    @AfterEach
    void cleanUp() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM summary_run_request");
            statement.executeUpdate("DELETE FROM journal_entry");
            statement.executeUpdate("DELETE FROM raw_record");
            statement.executeUpdate("DELETE FROM child_organization");
        }
    }

    @Test
    void 같은_묶음을_처음_두_번_동시에_눌러도_행은_하나이고_둘_다_받는다() throws Exception {
        LocalDateTime firstPress = LocalDateTime.of(2026, 10, 8, 17, 0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            int holderPid = backendPid(holder);
            // 먼저 누른 쪽이 요청 행을 넣고 아직 커밋하지 않았다.
            try (PreparedStatement statement = holder.prepareStatement(
                    "INSERT INTO summary_run_request (child_id, entry_date, institution_id, requested_at) "
                            + "VALUES (?, ?, ?, ?)")) {
                statement.setLong(1, CHILD);
                statement.setObject(2, DATE);
                statement.setLong(3, ORG);
                statement.setObject(4, firstPress);
                statement.executeUpdate();
            }
            Future<?> second = executor.submit(() -> summaryRunService.requestRun(TEACHER, CHILD, DATE));
            waitUntilBlockedBy(holderPid);
            holder.commit();
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(summaryRunRequestRepository.findAll()).singleElement().satisfies(request -> {
            assertThat(request.getRequestedAt()).isAfter(firstPress);
            assertThat(request.isPending()).isTrue();
        });
    }

    @Test
    void 처음_누르면_행을_만들고_다시_누르면_시각만_바꾼다() {
        summaryRunService.requestRun(TEACHER, CHILD, DATE);
        LocalDateTime first = summaryRunRequestRepository.findAll().get(0).getRequestedAt();

        summaryRunService.requestRun(TEACHER, CHILD, DATE);

        assertThat(summaryRunRequestRepository.findAll()).singleElement()
                .extracting(SummaryRunRequest::getRequestedAt)
                .satisfies(requestedAt -> assertThat(requestedAt).isAfterOrEqualTo(first));
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_backend_pid()");
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

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
        fail("[요약] 요청이 홀더의 요청 행 때문에 멈추지 않았습니다.");
    }
}
