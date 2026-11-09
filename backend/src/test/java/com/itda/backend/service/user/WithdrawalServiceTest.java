package com.itda.backend.service.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.child.ChildStatus;
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.ConsentRevokeReason;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.domain.invitation.Invitation;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.user.User;
import com.itda.backend.domain.user.WithdrawalStatus;
import com.itda.backend.dto.request.user.SignupRequest;
import com.itda.backend.exception.user.UserErrorCode;
import com.itda.backend.exception.user.UserException;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.TermsFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.user.TermsAgreementRepository;
import com.itda.backend.repository.user.UserRepository;
import com.itda.backend.service.consent.ConsentService;

/**
 * 회원 탈퇴 전체 흐름. 실제 실행기 · 트랜잭션 · 아이 연결 정리를 쓰고, 카카오 호출만 바꿔 끼운다. 잠금은 H2 라 한 프로세스
 * 안 잠금이다 — advisory lock 과 동시성은 PostgreSQL 통합 테스트({@code WithdrawalConcurrencyIntegrationTest})가 본다.
 *
 * <p>SpringBootTest 들은 같은 H2 DB 를 공유하고 롤백하지 않는다. 카카오 회원번호와 초대 코드는 호출마다 새로 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WithdrawalServiceTest {

    private static final AtomicLong NEXT_KAKAO_ID = new AtomicLong(
            7_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L));

    @Autowired private WithdrawalService withdrawalService;
    @Autowired private WithdrawalExecutor withdrawalExecutor;
    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private EntityManager entityManager;
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private TermsAgreementRepository termsAgreementRepository;

    @MockitoBean private KakaoUnlinkClient kakaoUnlinkClient;
    @MockitoSpyBean private ConsentService consentService;

    private Organization organization;

    @BeforeEach
    void setUp() {
        given(kakaoUnlinkClient.unlink(anyLong())).willReturn(KakaoUnlinkResult.UNLINKED);
        organization = persist(OrganizationFixture.center());
    }

    @AfterEach
    void resetSpy() {
        reset(consentService);
    }

    @Test
    void 보호자_두_명_중_한_명이_탈퇴하면_다른_보호자의_동의와_기관_공유는_그대로다() {
        User mother = parent();
        User father = parent();
        Child child = enrolledChild();
        link(child, mother);
        link(child, father);
        consent(child, mother);
        consent(child, father);

        WithdrawalSteps.Outcome outcome = withdrawalService.withdraw(principal(mother));

        assertThat(outcome).isEqualTo(WithdrawalSteps.Outcome.COMPLETED);
        verify(kakaoUnlinkClient).unlink(Long.parseLong(mother.getKakaoId()));
        User withdrawn = reload(mother);
        assertThat(withdrawn.isDeleted()).isTrue();
        assertThat(withdrawn.isWithdrawing()).isFalse();
        assertThat(withdrawn.getKakaoId()).isEqualTo("withdrawn:" + mother.getId());
        assertThat(withdrawn.getName()).isEqualTo(User.WITHDRAWN_NAME);
        assertThat(guardianLink(child, mother).isDeleted()).isTrue();
        assertThat(guardianLink(child, father).isDeleted()).isFalse();
        SharingConsent motherConsent = consentOf(child, mother);
        assertThat(motherConsent.isActive()).isFalse();
        assertThat(motherConsent.getRevokeReason()).isEqualTo(ConsentRevokeReason.GUARDIAN_UNLINKED);
        assertThat(consentOf(child, father).isActive()).isTrue();
        assertThat(reloadChild(child).getStatus()).isEqualTo(ChildStatus.ACTIVE);
    }

    @Test
    void 두_번째_아이_정리가_실패하면_전체가_되돌아가고_다시_돌면_카카오는_부르지_않고_정리만_한다() throws Exception {
        User mother = parent();
        Child first = enrolledChild();
        Child second = enrolledChild();
        link(first, mother);
        link(second, mother);
        consent(first, mother);
        consent(second, mother);
        willThrow(new IllegalStateException("두 번째 아이에서 실패"))
                .given(consentService).revokeForUnlinkedGuardian(
                        org.mockito.ArgumentMatchers.argThat(child -> child.getId().equals(second.getId())),
                        eq(mother.getId()), eq(mother.getId()));

        WithdrawalSteps.Outcome outcome = withdrawalService.withdraw(principal(mother));

        assertThat(outcome).isEqualTo(WithdrawalSteps.Outcome.IN_PROGRESS);
        assertThat(reload(mother).getWithdrawalStatus()).isEqualTo(WithdrawalStatus.KAKAO_UNLINKED);
        assertThat(guardianLink(first, mother).isDeleted()).isFalse();
        assertThat(guardianLink(second, mother).isDeleted()).isFalse();
        assertThat(consentOf(first, mother).isActive()).isTrue();

        reset(consentService);
        withdrawalExecutor.submit(mother.getId()).get(5, TimeUnit.SECONDS);

        assertThat(reload(mother).isDeleted()).isTrue();
        assertThat(guardianLink(first, mother).isDeleted()).isTrue();
        assertThat(guardianLink(second, mother).isDeleted()).isTrue();
        verify(kakaoUnlinkClient, times(1)).unlink(anyLong());
    }

    @Test
    void 탈퇴한_뒤_같은_카카오_계정으로_오면_새_회원이고_옛_출입증은_다시_연결해도_401이다() throws Exception {
        User mother = parent();
        Child child = enrolledChild();
        link(child, mother);
        Cookie oldLogin = cookieFor(mother);

        withdrawalService.withdraw(principal(mother));
        User again = userService.findOrCreateByKakaoId(mother.getKakaoId(), "김엄마");
        userService.completeSignup(principal(again), new SignupRequest("parent", null, null, null));
        link(child, again);

        assertThat(again.getId()).isNotEqualTo(mother.getId());
        assertThat(userService.getCurrentUser(principal(again)).termsAgreed()).isFalse();
        mockMvc.perform(get("/api/v1/auth/me").cookie(oldLogin))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_WITHDRAWN"));
        mockMvc.perform(get("/api/v1/guardians/me/children").cookie(oldLogin))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_WITHDRAWN"));
    }

    @Test
    void 가입하지_않은_사람도_탈퇴할_수_있다() {
        User pending = persist(User.pending(nextKakaoId(), "가입전"));

        assertThat(withdrawalService.withdraw(principal(pending))).isEqualTo(WithdrawalSteps.Outcome.COMPLETED);
        assertThat(reload(pending).isDeleted()).isTrue();
    }

    @Test
    void 기관_담당자는_카카오를_부르기_전에_403이다() {
        User staff = persist(UserFixture.organizationUser(nextKakaoId(), "선생님", organization.getId()));

        assertThatThrownBy(() -> withdrawalService.withdraw(principal(staff)))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(UserErrorCode.ORGANIZATION_WITHDRAWAL_NOT_SUPPORTED);
        verify(kakaoUnlinkClient, never()).unlink(anyLong());
        assertThat(reload(staff).isWithdrawing()).isFalse();
    }

    @Test
    void 이미_접수한_탈퇴를_다시_신청하면_409다() {
        User mother = parent();
        given(kakaoUnlinkClient.unlink(anyLong())).willReturn(KakaoUnlinkResult.UNKNOWN);
        withdrawalService.withdraw(principal(mother));

        assertThatThrownBy(() -> withdrawalService.withdraw(principal(mother)))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(UserErrorCode.WITHDRAWAL_IN_PROGRESS);
    }

    @Test
    void 개발용_로그인_회원은_카카오를_부르지_않는다() {
        User dev = persist(UserFixture.parent("dev:withdrawal-" + nextKakaoId(), "개발"));

        assertThat(withdrawalService.withdraw(principal(dev))).isEqualTo(WithdrawalSteps.Outcome.COMPLETED);
        verify(kakaoUnlinkClient, never()).unlink(anyLong());
    }

    @Test
    void 첫_호출이_분명히_실패하면_접수를_취소하고_회원은_그대로다() {
        User mother = parent();
        given(kakaoUnlinkClient.unlink(anyLong())).willReturn(KakaoUnlinkResult.FAILED);

        assertThat(withdrawalService.withdraw(principal(mother))).isEqualTo(WithdrawalSteps.Outcome.CANCELED);

        User after = reload(mother);
        assertThat(after.isDeleted()).isFalse();
        assertThat(after.isWithdrawing()).isFalse();
        assertThat(after.getKakaoUnlinkAttemptedAt()).isNull();
        assertThat(after.getKakaoId()).isEqualTo(mother.getKakaoId());
    }

    @Test
    void 첫_호출_실패_뒤_다시_신청해도_새로운_첫_호출이라_다시_취소된다() {
        User mother = parent();
        given(kakaoUnlinkClient.unlink(anyLong())).willReturn(KakaoUnlinkResult.FAILED);

        assertThat(withdrawalService.withdraw(principal(mother))).isEqualTo(WithdrawalSteps.Outcome.CANCELED);
        assertThat(withdrawalService.withdraw(principal(mother))).isEqualTo(WithdrawalSteps.Outcome.CANCELED);
        assertThat(reload(mother).isWithdrawing()).isFalse();
    }

    @Test
    void 결과를_모르면_처리_중으로_남고_그_뒤의_분명한_오류로는_취소하지_않는다() throws Exception {
        User mother = parent();
        given(kakaoUnlinkClient.unlink(anyLong())).willReturn(KakaoUnlinkResult.UNKNOWN);

        assertThat(withdrawalService.withdraw(principal(mother))).isEqualTo(WithdrawalSteps.Outcome.IN_PROGRESS);
        User afterUnknown = reload(mother);
        assertThat(afterUnknown.getWithdrawalStatus()).isEqualTo(WithdrawalStatus.REQUESTED);
        assertThat(afterUnknown.getKakaoUnlinkAttemptedAt()).isNotNull();

        // 이전 호출이 카카오에서 성공했을 수 있다. 이번 -401 은 이번 호출의 실패일 뿐이다
        given(kakaoUnlinkClient.unlink(anyLong())).willReturn(KakaoUnlinkResult.FAILED);
        withdrawalExecutor.submit(mother.getId()).get(5, TimeUnit.SECONDS);
        assertThat(reload(mother).getWithdrawalStatus()).isEqualTo(WithdrawalStatus.REQUESTED);

        given(kakaoUnlinkClient.unlink(anyLong())).willReturn(KakaoUnlinkResult.UNLINKED);
        withdrawalExecutor.submit(mother.getId()).get(5, TimeUnit.SECONDS);
        assertThat(reload(mother).isDeleted()).isTrue();
    }

    @Test
    void 처리_중에는_다른_보호_API_를_막고_auth_me_로_진행을_알린다() throws Exception {
        User mother = parent();
        given(kakaoUnlinkClient.unlink(anyLong())).willReturn(KakaoUnlinkResult.UNKNOWN);
        withdrawalService.withdraw(principal(mother));

        mockMvc.perform(get("/api/v1/auth/me").cookie(cookieFor(mother)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.withdrawal").value("in_progress"));
        mockMvc.perform(get("/api/v1/guardians/me/children").cookie(cookieFor(mother)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WITHDRAWAL_IN_PROGRESS"));

        User relogin = userService.findOrCreateByKakaoId(mother.getKakaoId(), "새이름");
        assertThat(relogin.getId()).isEqualTo(mother.getId());
        assertThat(reload(mother).getName()).isEqualTo(mother.getName());
    }

    /**
     * API 로 부르면 요청 동안 영속성 컨텍스트가 열려 있다(open-in-view). 그래도 실행기가 커밋한 결과를 읽어 204 로 끝내고
     * 쿠키를 지워야 한다 — 접수 때 읽은 옛 회원을 다시 보면 늘 202 가 된다.
     */
    @Test
    void API_로_탈퇴하면_실행기가_마친_결과를_읽어_204_로_끝낸다() throws Exception {
        User mother = parent();

        mockMvc.perform(withCsrf(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/auth/me"), cookieFor(mother)))
                .andExpect(status().isNoContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .stringValues("Set-Cookie", org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.startsWith(JwtCookie.NAME + "=;"))));
        assertThat(reload(mother).isDeleted()).isTrue();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withCsrf(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, Cookie login)
            throws Exception {
        Cookie csrf = mockMvc.perform(get("/api/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        return request.cookie(login, csrf).header("X-XSRF-TOKEN", csrf.getValue());
    }

    // ── 준비 ──────────────────────────────────────────────

    private User parent() {
        User user = persist(UserFixture.parent(nextKakaoId(), "김보호"));
        // 보호 API 를 MockMvc 로 부르는 테스트가 약관 확인 필터에 막히지 않게 한다
        TermsFixture.agreeAll(termsAgreementRepository, user);
        return user;
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

    private void consent(Child child, User guardian) {
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.persist(SharingConsent.grant(child.getId(), guardian.getId(), organization.getId(),
                    List.of(ConsentField.DAILY_SUMMARY)));
            entityManager.find(Child.class, child.getId()).activate();
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

    private Child reloadChild(Child child) {
        return transactionTemplate.execute(status -> entityManager.find(Child.class, child.getId()));
    }

    private ChildGuardian guardianLink(Child child, User guardian) {
        return transactionTemplate.execute(status -> entityManager.createQuery(
                        "select g from ChildGuardian g where g.childId = :childId and g.userId = :userId",
                        ChildGuardian.class)
                .setParameter("childId", child.getId())
                .setParameter("userId", guardian.getId())
                .getSingleResult());
    }

    private SharingConsent consentOf(Child child, User guardian) {
        return transactionTemplate.execute(status -> entityManager.createQuery(
                        "select c from SharingConsent c where c.childId = :childId and c.guardianId = :guardianId",
                        SharingConsent.class)
                .setParameter("childId", child.getId())
                .setParameter("guardianId", guardian.getId())
                .getSingleResult());
    }

    private Cookie cookieFor(User user) {
        return new Cookie(JwtCookie.NAME, jwtProvider.createToken(String.valueOf(user.getId())));
    }

    private static String principal(User user) {
        return String.valueOf(user.getId());
    }

    private static String nextKakaoId() {
        return String.valueOf(NEXT_KAKAO_ID.getAndIncrement());
    }

    private static String nextInviteCode() {
        StringBuilder code = new StringBuilder();
        String alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
        for (int i = 0; i < Invitation.CODE_LENGTH; i++) {
            code.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return code.toString();
    }
}
