package com.itda.backend.service.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.domain.invitation.Invitation;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.user.User;
import com.itda.backend.domain.user.WithdrawalStatus;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.security.ApiPaths;
import com.itda.backend.repository.user.UserRepository;

/**
 * 카카오 연결 해제 웹훅. 실제 보안 필터 · 실행기 · 정리를 쓰고 카카오 호출만 바꿔 끼운다. 웹훅이 카카오를 다시 부르지 않는지도 본다.
 * 테스트 설정의 대표 어드민 키는 {@code test-admin-key}, 앱 ID 는 {@code 123456} 이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KakaoUnlinkWebhookTest {

    private static final String AUTH = "KakaoAK test-admin-key";
    private static final String APP_ID = "123456";
    private static final AtomicLong NEXT_KAKAO_ID = new AtomicLong(
            8_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L));

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private EntityManager entityManager;
    @Autowired private WithdrawalSteps steps;
    @Autowired private WithdrawalExecutor executor;

    @MockitoBean private KakaoUnlinkClient kakaoUnlinkClient;

    private Organization organization;

    @BeforeEach
    void setUp() {
        given(kakaoUnlinkClient.unlink(anyLong())).willReturn(KakaoUnlinkResult.UNLINKED);
        // 웹훅은 탈퇴 전에 카카오에 지금 연결 상태를 묻는다. 기본은 정말 끊겨 있음
        given(kakaoUnlinkClient.linkStatus(anyLong())).willReturn(KakaoLinkStatus.NOT_LINKED);
        organization = persist(OrganizationFixture.center());
    }

    @Test
    void 어드민_키가_없거나_다르면_401이고_아무것도_바꾸지_않는다() throws Exception {
        User mother = persist(UserFixture.parent(nextKakaoId()));

        mockMvc.perform(post(ApiPaths.KAKAO_UNLINK_WEBHOOK).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("app_id", APP_ID).param("user_id", mother.getKakaoId())
                        .param("referrer_type", "UNLINK_FROM_APPS"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("KAKAO_WEBHOOK_UNAUTHORIZED"));
        mockMvc.perform(webhook(post(ApiPaths.KAKAO_UNLINK_WEBHOOK), mother.getKakaoId())
                        .header("Authorization", "KakaoAK wrong-key"))
                .andExpect(status().isUnauthorized());

        assertThat(reload(mother).isWithdrawing()).isFalse();
        assertThat(reload(mother).isDeleted()).isFalse();
    }

    @Test
    void 보호자는_카카오를_다시_부르지_않고_아이_연결을_정리해_탈퇴한다() throws Exception {
        User mother = persist(UserFixture.parent(nextKakaoId()));
        Child child = enrolledChild();
        link(child, mother);

        // CSRF 토큰 · 로그인 쿠키 없이 들어온다
        mockMvc.perform(webhook(post(ApiPaths.KAKAO_UNLINK_WEBHOOK), mother.getKakaoId()))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        waitUntilDeleted(mother);
        User after = reload(mother);
        assertThat(after.getKakaoId()).isEqualTo("withdrawn:" + mother.getId());
        assertThat(guardianLink(child, mother).isDeleted()).isTrue();
        verify(kakaoUnlinkClient, never()).unlink(anyLong());
    }

    @Test
    void GET_쿼리로_와도_처리한다() throws Exception {
        User pending = persist(User.pending(nextKakaoId()));

        mockMvc.perform(get(ApiPaths.KAKAO_UNLINK_WEBHOOK).header("Authorization", AUTH)
                        .queryParam("app_id", APP_ID).queryParam("user_id", pending.getKakaoId())
                        .queryParam("referrer_type", "ACCOUNT_DELETE"))
                .andExpect(status().isOk());

        waitUntilDeleted(pending);
    }

    @Test
    void 없는_회원이어도_200이다() throws Exception {
        mockMvc.perform(webhook(post(ApiPaths.KAKAO_UNLINK_WEBHOOK), nextKakaoId()))
                .andExpect(status().isOk());
    }

    @Test
    void 다른_앱의_웹훅은_무시한다() throws Exception {
        User mother = persist(UserFixture.parent(nextKakaoId()));

        mockMvc.perform(post(ApiPaths.KAKAO_UNLINK_WEBHOOK).header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("app_id", "999").param("user_id", mother.getKakaoId())
                        .param("referrer_type", "UNLINK_FROM_APPS"))
                .andExpect(status().isOk());

        assertThat(reload(mother).isWithdrawing()).isFalse();
    }

    @Test
    void 기관_담당자는_계정을_남긴다() throws Exception {
        User staff = persist(UserFixture.organizationUser(nextKakaoId(), organization.getId()));

        mockMvc.perform(webhook(post(ApiPaths.KAKAO_UNLINK_WEBHOOK), staff.getKakaoId()))
                .andExpect(status().isOk());

        User after = reload(staff);
        assertThat(after.isWithdrawing()).isFalse();
        assertThat(after.isDeleted()).isFalse();
    }

    @Test
    void 이미_탈퇴를_처리하는_중이면_실행기가_이어서_끝낸다() throws Exception {
        User mother = persist(UserFixture.parent(nextKakaoId()));
        transactionTemplate.executeWithoutResult(status ->
                entityManager.find(User.class, mother.getId()).requestWithdrawal(LocalDateTime.now()));

        mockMvc.perform(webhook(post(ApiPaths.KAKAO_UNLINK_WEBHOOK), mother.getKakaoId()))
                .andExpect(status().isOk());

        // 카카오에 물어 끊긴 것을 확인했으므로 해제 확정으로 바꾸고 정리만 한다 — 연결 해제는 다시 부르지 않는다
        waitUntilDeleted(mother);
        verify(kakaoUnlinkClient, never()).unlink(anyLong());
    }

    /**
     * 옛 연결의 웹훅이 늦게 왔다. 그 사이 이전 계정은 탈퇴를 마쳤고, 같은 카카오 계정으로 다시 가입한 새 계정이 있다. 웹훅은
     * 카카오 회원번호로 새 계정을 찾지만, 카카오에 물어 보면 지금은 연결돼 있으므로 새 계정을 지우지 않는다.
     */
    @Test
    void 옛_연결의_늦은_웹훅은_같은_카카오로_재가입한_새_계정을_지우지_않는다() throws Exception {
        String kakaoId = nextKakaoId();
        User old = persist(UserFixture.parent(kakaoId));
        transactionTemplate.executeWithoutResult(status -> UserFixture.withdrawn(entityManager.find(User.class, old.getId())));
        User again = persist(UserFixture.parent(kakaoId));
        given(kakaoUnlinkClient.linkStatus(anyLong())).willReturn(KakaoLinkStatus.LINKED);

        mockMvc.perform(webhook(post(ApiPaths.KAKAO_UNLINK_WEBHOOK), kakaoId))
                .andExpect(status().isOk());

        waitUntilReportResolved(again);
        User after = reload(again);
        assertThat(after.isDeleted()).isFalse();
        assertThat(after.isWithdrawing()).isFalse();
        verify(kakaoUnlinkClient, never()).unlink(anyLong());
    }

    @Test
    void 카카오_연결_상태를_모르면_알림을_남겨_두고_아무것도_지우지_않는다() throws Exception {
        User mother = persist(UserFixture.parent(nextKakaoId()));
        given(kakaoUnlinkClient.linkStatus(anyLong())).willReturn(KakaoLinkStatus.UNKNOWN);

        mockMvc.perform(webhook(post(ApiPaths.KAKAO_UNLINK_WEBHOOK), mother.getKakaoId()))
                .andExpect(status().isOk());
        executor.submit(mother.getId()).get(5, TimeUnit.SECONDS);

        User after = reload(mother);
        assertThat(after.hasKakaoUnlinkReport()).isTrue();
        assertThat(after.isWithdrawing()).isFalse();
        assertThat(after.isDeleted()).isFalse();

        // 나중에 끊긴 것이 확인되면 탈퇴한다
        given(kakaoUnlinkClient.linkStatus(anyLong())).willReturn(KakaoLinkStatus.NOT_LINKED);
        executor.submit(mother.getId()).get(5, TimeUnit.SECONDS);
        assertThat(reload(mother).isDeleted()).isTrue();
    }

    @Test
    void 연결돼_있으면_사용자가_직접_신청한_탈퇴는_원래대로_이어진다() throws Exception {
        User mother = persist(UserFixture.parent(nextKakaoId()));
        transactionTemplate.executeWithoutResult(status ->
                entityManager.find(User.class, mother.getId()).requestWithdrawal(LocalDateTime.now()));
        given(kakaoUnlinkClient.linkStatus(anyLong())).willReturn(KakaoLinkStatus.LINKED);

        mockMvc.perform(webhook(post(ApiPaths.KAKAO_UNLINK_WEBHOOK), mother.getKakaoId()))
                .andExpect(status().isOk());

        // 지난 연결의 알림은 지우고, 직접 신청한 탈퇴는 연결 해제를 불러 끝낸다
        waitUntilDeleted(mother);
        verify(kakaoUnlinkClient).unlink(Long.parseLong(mother.getKakaoId()));
    }

    @Test
    void 탈퇴를_열지_않았으면_검증만_하고_무시한다() {
        User mother = persist(UserFixture.parent(nextKakaoId()));
        KakaoUnlinkWebhookService closed = new KakaoUnlinkWebhookService(steps, executor, new WithdrawalProperties(
                false, java.time.Duration.ofSeconds(1), "local",
                new WithdrawalProperties.Kakao("http://unused", "test-admin-key", APP_ID, null, null), null));

        closed.handle(AUTH, APP_ID, mother.getKakaoId(), "UNLINK_FROM_APPS");

        assertThat(reload(mother).isWithdrawing()).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> closed.handle("KakaoAK wrong", APP_ID, mother.getKakaoId(), "UNLINK_FROM_APPS"))
                .isInstanceOf(com.itda.backend.exception.auth.AuthException.class);
    }

    @Test
    void 같은_웹훅이_두_번_와도_한_번만_탈퇴한다() throws Exception {
        User mother = persist(UserFixture.parent(nextKakaoId()));

        mockMvc.perform(webhook(post(ApiPaths.KAKAO_UNLINK_WEBHOOK), mother.getKakaoId()))
                .andExpect(status().isOk());
        waitUntilDeleted(mother);
        LocalDateTime deletedAt = reload(mother).getDeletedAt();

        mockMvc.perform(webhook(post(ApiPaths.KAKAO_UNLINK_WEBHOOK), mother.getKakaoId()))
                .andExpect(status().isOk());

        assertThat(reload(mother).getDeletedAt()).isEqualTo(deletedAt);
        assertThat(reload(mother).getWithdrawalStatus()).isNull();
    }

    @Test
    void 옛_방식_탈퇴_행이면_남은_식별정보를_지운다() throws Exception {
        User legacy = persist(UserFixture.legacyDeleted(UserFixture.parent(nextKakaoId())));

        mockMvc.perform(webhook(post(ApiPaths.KAKAO_UNLINK_WEBHOOK), legacy.getKakaoId()))
                .andExpect(status().isOk());

        assertThat(reload(legacy).getKakaoId()).isEqualTo("withdrawn:" + legacy.getId());
        assertThat(reload(legacy).getWithdrawalStatus()).isNotEqualTo(WithdrawalStatus.REQUESTED);
    }

    // ── 준비 ──────────────────────────────────────────────

    private static MockHttpServletRequestBuilder webhook(MockHttpServletRequestBuilder request, String kakaoId) {
        return request.header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("app_id", APP_ID)
                .param("user_id", kakaoId)
                .param("referrer_type", "UNLINK_FROM_APPS");
    }

    private void waitUntilReportResolved(User user) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (!reload(user).hasKakaoUnlinkReport()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("웹훅 알림을 확인하지 않았습니다.");
    }

    private void waitUntilDeleted(User user) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (reload(user).isDeleted()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("탈퇴가 끝나지 않았습니다.");
    }

    private Child enrolledChild() {
        Child child = persist(Child.of("김하늘", LocalDate.of(2017, 3, 14)));
        persist(ChildOrganization.of(child.getId(), organization.getId()));
        return child;
    }

    private void link(Child child, User guardian) {
        transactionTemplate.executeWithoutResult(status -> {
            Invitation invitation = Invitation.issue(child.getId(), organization.getId(), nextInviteCode(),
                    LocalDateTime.now().plusDays(1));
            entityManager.persist(invitation);
            entityManager.persist(ChildGuardian.link(child.getId(), guardian.getId(), invitation.getId()));
        });
    }

    private ChildGuardian guardianLink(Child child, User guardian) {
        return transactionTemplate.execute(status -> entityManager.createQuery(
                        "select g from ChildGuardian g where g.childId = :childId and g.userId = :userId",
                        ChildGuardian.class)
                .setParameter("childId", child.getId())
                .setParameter("userId", guardian.getId())
                .getSingleResult());
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

    private static String nextKakaoId() {
        return String.valueOf(NEXT_KAKAO_ID.getAndIncrement());
    }

    private static String nextInviteCode() {
        String alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < Invitation.CODE_LENGTH; i++) {
            code.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return code.toString();
    }
}
