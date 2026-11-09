package com.itda.backend.service.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.domain.invitation.Invitation;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.user.KakaoUnlinkWebhook;
import com.itda.backend.domain.user.User;
import com.itda.backend.domain.user.UserRole;
import com.itda.backend.fixture.LockContention;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.TermsFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.user.KakaoUnlinkWebhookRepository;
import com.itda.backend.repository.user.TermsAgreementRepository;
import com.itda.backend.repository.user.UserRepository;
import com.itda.backend.service.invitation.InvitationService;
import com.zaxxer.hikari.HikariDataSource;

/**
 * 회원 탈퇴의 동시성을 PostgreSQL 에서 재현한다 — 회원 행 잠금으로 탈퇴와 초대 수락 · 가입이 줄을 서는지, advisory lock 이
 * 카카오 호출을 한 줄로 세우는지, 잠금 연결을 제대로 돌려주거나 버리는지.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class WithdrawalConcurrencyIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("itda_withdrawal_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private EntityManager entityManager;
    @Autowired private DataSource dataSource;
    @Autowired private UserRepository userRepository;
    @Autowired private TermsAgreementRepository termsAgreementRepository;
    @Autowired private InvitationService invitationService;
    @Autowired private UserService userService;
    @Autowired private WithdrawalSteps steps;
    @Autowired private WithdrawalExecutor withdrawalExecutor;
    @Autowired private KakaoUnlinkWebhookRepository webhookRepository;
    // 기본값 auto 가 PostgreSQL 을 보고 운영과 같은 세션 advisory lock 을 고른다 (잠금 연결 테스트가 그것을 전제한다)
    @Autowired private WithdrawalLocks locks;

    @MockitoBean private KakaoUnlinkClient kakaoUnlinkClient;

    private LockContention contention;
    private Organization organization;
    private int nextKakaoId = 1_000;

    @Test
    void PostgreSQL_이면_advisory_lock_을_고른다() {
        assertThat(locks).isInstanceOf(AdvisoryWithdrawalLocks.class);
    }

    @BeforeEach
    void setUp() {
        contention = new LockContention(transactionTemplate, entityManager, dataSource);
        given(kakaoUnlinkClient.unlink(anyLong())).willReturn(KakaoUnlinkResult.UNLINKED);
        organization = persist(OrganizationFixture.center());
    }

    @AfterEach
    void cleanUp() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("TRUNCATE sharing_consent, child_organization_request, child_guardian, invitation,"
                    + " child_organization, child, terms_agreement, revoked_session, organization, kakao_unlink_webhook, users CASCADE");
        }
    }

    // ── 회원 행 잠금: 탈퇴 × 초대 수락 ─────────────────────

    @Test
    void 초대_수락이_먼저_잠그면_탈퇴가_기다렸다가_새_연결까지_끊는다() throws Exception {
        User mother = parent();
        Child child = enrolledChild();
        String code = invite(child);

        LockContention.Held<Object> held = contention.firstHoldsThen(
                () -> invitationService.accept(principal(mother), code),
                () -> mockMvc.perform(withCsrf(delete("/api/v1/auth/me").param("userId", principal(mother)), auth(mother))).andReturn());

        assertThat(held.second().getResponse().getStatus()).isEqualTo(204);
        assertThat(reload(mother).isDeleted()).isTrue();
        assertThat(guardianLink(child, mother).isDeleted()).isTrue();
    }

    @Test
    void 탈퇴_접수가_먼저_잠그면_초대_수락은_기다렸다가_409다() throws Exception {
        User mother = parent();
        Child child = enrolledChild();
        String code = invite(child);

        LockContention.Held<Long> held = contention.firstHoldsThen(
                () -> steps.accept(mother.getId()),
                () -> mockMvc.perform(withCsrf(post("/api/v1/guardians/me/invitations/accept"), auth(mother))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}")).andReturn());

        assertThat(held.second().getResponse().getStatus()).isEqualTo(409);
        assertThat(held.second().getResponse().getContentAsString()).contains("WITHDRAWAL_IN_PROGRESS");
        assertThat(guardianLinks(mother)).isEmpty();
    }

    // ── 회원 행 잠금: 가입 전 탈퇴 × 기관 가입 ──────────────

    @Test
    void 가입_전_탈퇴가_먼저면_기관_가입은_409다() throws Exception {
        User pending = persist(User.pending(String.valueOf(nextKakaoId++)));

        LockContention.Held<Long> held = contention.firstHoldsThen(
                () -> steps.accept(pending.getId()),
                () -> mockMvc.perform(withCsrf(post("/api/v1/auth/signup"), auth(pending))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orgSignup())).andReturn());

        assertThat(held.second().getResponse().getStatus()).isEqualTo(409);
        assertThat(held.second().getResponse().getContentAsString()).contains("WITHDRAWAL_IN_PROGRESS");
        assertThat(reload(pending).isSignupCompleted()).isFalse();
    }

    @Test
    void 기관_가입이_먼저면_탈퇴는_403이고_카카오를_부르지_않는다() throws Exception {
        User pending = persist(User.pending(String.valueOf(nextKakaoId++)));

        LockContention.Held<Object> held = contention.firstHoldsThen(
                () -> userService.completeSignup(principal(pending), new com.itda.backend.dto.request.user.SignupRequest(
                        "org", "새싹센터", com.itda.backend.domain.organization.OrganizationType.CENTER,
                        OrganizationFixture.nextBusinessNumber())),
                () -> mockMvc.perform(withCsrf(delete("/api/v1/auth/me").param("userId", principal(pending)), auth(pending))).andReturn());

        assertThat(held.second().getResponse().getStatus()).isEqualTo(403);
        assertThat(held.second().getResponse().getContentAsString()).contains("ORGANIZATION_WITHDRAWAL_NOT_SUPPORTED");
        assertThat(reload(pending).getRole()).isEqualTo(UserRole.ORGANIZATION);
        verify(kakaoUnlinkClient, times(0)).unlink(anyLong());
    }

    // ── 아이 잠금 순서 ──────────────────────────────────────

    @Test
    void 같은_아이들을_둔_두_보호자의_정리가_겹쳐도_멈추지_않고_둘_다_끝난다() throws Exception {
        User mother = parent();
        User father = parent();
        Child first = enrolledChild();
        Child second = enrolledChild();
        // 연결 순서를 서로 반대로 둔다 — 연결 순서대로 잠그면 서로 기다리다 멈춘다
        link(first, mother);
        link(second, mother);
        link(second, father);
        link(first, father);
        readyForCleanup(mother);
        readyForCleanup(father);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> a = pool.submit(() -> {
                start.await();
                steps.cleanup(mother.getId());
                return null;
            });
            Future<?> b = pool.submit(() -> {
                start.await();
                steps.cleanup(father.getId());
                return null;
            });
            start.countDown();
            a.get(20, TimeUnit.SECONDS);
            b.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(reload(mother).isDeleted()).isTrue();
        assertThat(reload(father).isDeleted()).isTrue();
        assertThat(guardianLinks(mother)).allMatch(ChildGuardian::isDeleted);
        assertThat(guardianLinks(father)).allMatch(ChildGuardian::isDeleted);
    }

    // ── advisory lock: 카카오 호출을 한 줄로 ────────────────

    @Test
    void 잠금을_쥔_실행기가_멈춘_동안_다른_실행기는_카카오를_부르지_않고_넘어간다() throws Exception {
        User mother = parent();
        steps.accept(mother.getId());
        CountDownLatch calling = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        given(kakaoUnlinkClient.unlink(anyLong())).willAnswer(invocation -> {
            calling.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("풀어 주지 않았습니다.");
            }
            return KakaoUnlinkResult.UNLINKED;
        });
        WithdrawalExecutor otherProcess = new WithdrawalExecutor(locks, steps, kakaoUnlinkClient);

        Future<Void> first = withdrawalExecutor.submit(mother.getId());
        assertThat(calling.await(10, TimeUnit.SECONDS)).isTrue();
        // 시간이 지나도 넘겨받지 않는다 — 잠금을 못 잡으면 그냥 넘어간다
        otherProcess.process(mother.getId());
        verify(kakaoUnlinkClient, times(1)).unlink(anyLong());

        release.countDown();
        first.get(10, TimeUnit.SECONDS);
        otherProcess.destroy();

        assertThat(reload(mother).isDeleted()).isTrue();
        verify(kakaoUnlinkClient, times(1)).unlink(anyLong());
    }

    @Test
    void 잠금을_쥔_세션이_끊기면_잠금이_풀린다() throws Exception {
        WithdrawalLocks.Held held = locks.tryAcquire(42L).orElseThrow();
        int pid = backendPid(held);
        try {
            assertThat(locks.tryAcquire(42L)).isEmpty();
            terminate(pid);

            Optional<WithdrawalLocks.Held> again = locks.tryAcquire(42L);
            assertThat(again).isPresent();
            again.get().close();
        } finally {
            held.close();
        }
    }

    @Test
    void 회원_id_가_integer_범위를_넘어도_그대로_잠근다() {
        long beyondInt = (1L << 31) + 1;
        long sameLow32Bits = beyondInt + (1L << 32);
        WithdrawalLocks.Held held = locks.tryAcquire(beyondInt).orElseThrow();
        try {
            assertThat(locks.tryAcquire(beyondInt)).isEmpty();
            // 32비트로 잘랐다면 같은 키가 되어 막혔을 것이다
            Optional<WithdrawalLocks.Held> other = locks.tryAcquire(sameLow32Bits);
            assertThat(other).isPresent();
            other.get().close();
        } finally {
            held.close();
        }
        Optional<WithdrawalLocks.Held> after = locks.tryAcquire(beyondInt);
        assertThat(after).isPresent();
        after.get().close();
    }

    @Test
    void 정상으로_풀면_연결을_풀에_돌려주고_잠금은_남지_않는다() throws Exception {
        WithdrawalLocks.Held held = locks.tryAcquire(7L).orElseThrow();
        int pid = backendPid(held);

        held.close();

        assertThat(isAlive(pid)).isTrue();
        assertThat(advisoryLocksOf(pid)).isZero();
    }

    @Test
    void 해제에_실패하면_그_연결은_풀로_돌아가지_않고_닫힌다() throws Exception {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        WithdrawalLocks.Held held = locks.tryAcquire(8L).orElseThrow();
        int pid = backendPid(held);
        int totalBefore = hikari.getHikariPoolMXBean().getTotalConnections();
        // 같은 세션에서 잠금을 미리 풀어 해제가 false 를 돌려주게 만든다
        try (Statement statement = lockConnection(held).createStatement()) {
            statement.execute("SELECT pg_advisory_unlock_all()");
        }

        held.close();

        waitUntil(() -> !isAlive(pid), "버린 연결의 세션이 닫히지 않았습니다.");
        assertThat(hikari.getHikariPoolMXBean().getTotalConnections()).isLessThan(totalBefore);
        try (Connection next = dataSource.getConnection();
             ResultSet resultSet = next.createStatement().executeQuery("SELECT pg_backend_pid()")) {
            resultSet.next();
            assertThat(resultSet.getInt(1)).isNotEqualTo(pid);
        }
    }

    // ── 웹훅 수신함 ────────────────────────────────────────

    /**
     * 웹훅을 회원 행으로 옮길 때는 그 행을 기다리지 않는다(NOWAIT). 다른 트랜잭션이 행을 잠그고 있으면 바로 잠금 예외로 끝나고 줄은
     * 남는다 — 한 회원 행 때문에 뒤의 웹훅 · 주기 작업이 밀리지 않는다. 잠금이 풀리면 옮겨진다.
     */
    @Test
    void 잠긴_회원의_웹훅은_기다리지_않고_실패해_줄에_남는다() throws Exception {
        User mother = parent();
        KakaoUnlinkWebhook webhook = persist(KakaoUnlinkWebhook.received(mother.getKakaoId(), LocalDateTime.now()));

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("select id from users where id = ? for update")) {
                lock.setLong(1, mother.getId());
                lock.executeQuery();
            }

            long started = System.nanoTime();
            assertThatThrownBy(() -> steps.absorbWebhook(webhook.getId()))
                    .isInstanceOf(PessimisticLockingFailureException.class);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(500);
            assertThat(webhookRepository.existsById(webhook.getId())).isTrue();
            holder.rollback();
        }

        assertThat(steps.absorbWebhook(webhook.getId())).contains(mother.getId());
        assertThat(webhookRepository.existsById(webhook.getId())).isFalse();
        assertThat(reload(mother).hasKakaoUnlinkReport()).isTrue();
    }

    // ── 준비 ──────────────────────────────────────────────

    private User parent() {
        User user = persist(UserFixture.parent(String.valueOf(nextKakaoId++)));
        TermsFixture.agreeAll(termsAgreementRepository, user);
        return user;
    }

    private Child enrolledChild() {
        Child child = persist(Child.of("김하늘", LocalDate.of(2017, 3, 14)));
        persist(ChildOrganization.of(child.getId(), organization.getId()));
        return child;
    }

    private String invite(Child child) {
        String code = "WD" + String.format("%06d", nextKakaoId++).replace('0', 'A').replace('1', 'B');
        persist(Invitation.issue(child.getId(), organization.getId(), code, LocalDateTime.now().plusDays(1)));
        return code;
    }

    private void link(Child child, User guardian) {
        String code = invite(child);
        transactionTemplate.executeWithoutResult(status -> {
            Invitation invitation = entityManager.createQuery(
                            "select i from Invitation i where i.code = :code", Invitation.class)
                    .setParameter("code", code).getSingleResult();
            entityManager.persist(ChildGuardian.link(child.getId(), guardian.getId(), invitation.getId()));
        });
    }

    private void readyForCleanup(User user) {
        transactionTemplate.executeWithoutResult(status -> {
            User found = entityManager.find(User.class, user.getId());
            found.requestWithdrawal(LocalDateTime.now());
            found.markKakaoUnlinked();
        });
    }

    private <T> T persist(T entity) {
        return transactionTemplate.execute(status -> {
            entityManager.persist(entity);
            return entity;
        });
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private ChildGuardian guardianLink(Child child, User guardian) {
        return guardianLinks(guardian).stream().filter(g -> g.getChildId().equals(child.getId())).findFirst()
                .orElseThrow();
    }

    private List<ChildGuardian> guardianLinks(User guardian) {
        return transactionTemplate.execute(status -> entityManager.createQuery(
                        "select g from ChildGuardian g where g.userId = :userId", ChildGuardian.class)
                .setParameter("userId", guardian.getId())
                .getResultList());
    }

    private String orgSignup() {
        return "{\"role\":\"org\",\"organizationName\":\"새싹센터\",\"organizationType\":\"CENTER\","
                + "\"businessNumber\":\"" + OrganizationFixture.nextBusinessNumber() + "\"}";
    }

    private Cookie auth(User user) {
        return new Cookie(JwtCookie.NAME, jwtProvider.createToken(principal(user)));
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request, Cookie... cookies)
            throws Exception {
        Cookie csrf = mockMvc.perform(get("/api/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        Cookie[] all = new Cookie[cookies.length + 1];
        System.arraycopy(cookies, 0, all, 0, cookies.length);
        all[cookies.length] = csrf;
        return request.cookie(all).header("X-XSRF-TOKEN", csrf.getValue());
    }

    private static String principal(User user) {
        return String.valueOf(user.getId());
    }

    private static Connection lockConnection(WithdrawalLocks.Held held) {
        return (Connection) ReflectionTestUtils.getField(held, "connection");
    }

    private static int backendPid(WithdrawalLocks.Held held) throws SQLException {
        try (Statement statement = lockConnection(held).createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT pg_backend_pid()")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private void terminate(int pid) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT pg_terminate_backend(?)")) {
            statement.setInt(1, pid);
            statement.executeQuery().close();
        }
        try {
            waitUntil(() -> !isAlive(pid), "끊은 세션이 남아 있습니다.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isAlive(int pid) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM pg_stat_activity WHERE pid = ?")) {
            statement.setInt(1, pid);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) > 0;
            }
        }
    }

    private int advisoryLocksOf(int pid) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND pid = ?")) {
            statement.setInt(1, pid);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private interface Condition {
        boolean holds() throws SQLException;
    }

    private static void waitUntil(Condition condition, String message) throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.holds()) {
                return;
            }
            Thread.sleep(50);
        }
        fail(message);
    }
}
