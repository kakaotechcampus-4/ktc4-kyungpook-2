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
    @Autowired private WithdrawalSteps withdrawalSteps;
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

        WithdrawalSteps.Outcome outcome = withdrawalService.withdraw(principal(mother), principal(mother));

        assertThat(outcome).isEqualTo(WithdrawalSteps.Outcome.COMPLETED);
        verify(kakaoUnlinkClient).unlink(Long.parseLong(mother.getKakaoId()));
        User withdrawn = reload(mother);
        assertThat(withdrawn.isDeleted()).isTrue();
        assertThat(withdrawn.isWithdrawing()).isFalse();
        assertThat(withdrawn.getKakaoId()).isEqualTo("withdrawn:" + mother.getId());
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

        WithdrawalSteps.Outcome outcome = withdrawalService.withdraw(principal(mother), principal(mother));

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

        withdrawalService.withdraw(principal(mother), principal(mother));
        User again = userService.findOrCreateByKakaoId(mother.getKakaoId());
        userService.completeSignup(principal(again), new SignupRequest("parent", null, null, null, null));
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
        User pending = persist(User.pending(nextKakaoId()));

        assertThat(withdrawalService.withdraw(principal(pending), principal(pending))).isEqualTo(WithdrawalSteps.Outcome.COMPLETED);
        assertThat(reload(pending).isDeleted()).isTrue();
    }

    @Test
    void 기관_담당자는_카카오를_부르기_전에_403이다() {
        User staff = persist(UserFixture.organizationUser(nextKakaoId(), organization.getId()));

        assertThatThrownBy(() -> withdrawalService.withdraw(principal(staff), principal(staff)))
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
        withdrawalService.withdraw(principal(mother), principal(mother));

        assertThatThrownBy(() -> withdrawalService.withdraw(principal(mother), principal(mother)))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(UserErrorCode.WITHDRAWAL_IN_PROGRESS);
    }

    @Test
    void 개발용_로그인_회원은_카카오를_부르지_않는다() {
        User dev = persist(UserFixture.parent("dev:withdrawal-" + nextKakaoId()));

        assertThat(withdrawalService.withdraw(principal(dev), principal(dev))).isEqualTo(WithdrawalSteps.Outcome.COMPLETED);
        verify(kakaoUnlinkClient, never()).unlink(anyLong());
    }

    @Test
    void 첫_호출이_분명히_실패하면_접수를_취소하고_회원은_그대로다() {
        User mother = parent();
        given(kakaoUnlinkClient.unlink(anyLong())).willReturn(KakaoUnlinkResult.FAILED);

        assertThat(withdrawalService.withdraw(principal(mother), principal(mother))).isEqualTo(WithdrawalSteps.Outcome.CANCELED);

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

        assertThat(withdrawalService.withdraw(principal(mother), principal(mother))).isEqualTo(WithdrawalSteps.Outcome.CANCELED);
        assertThat(withdrawalService.withdraw(principal(mother), principal(mother))).isEqualTo(WithdrawalSteps.Outcome.CANCELED);
        assertThat(reload(mother).isWithdrawing()).isFalse();
    }

    @Test
    void 결과를_모르면_처리_중으로_남고_그_뒤의_분명한_오류로는_취소하지_않는다() throws Exception {
        User mother = parent();
        given(kakaoUnlinkClient.unlink(anyLong())).willReturn(KakaoUnlinkResult.UNKNOWN);

        assertThat(withdrawalService.withdraw(principal(mother), principal(mother))).isEqualTo(WithdrawalSteps.Outcome.IN_PROGRESS);
        User afterUnknown = reload(mother);
        assertThat(afterUnknown.getWithdrawalStatus()).isEqualTo(WithdrawalStatus.REQUESTED);
        assertThat(afterUnknown.getKakaoUnlinkAttemptedAt()).isNotNull();

        // 이전 호출이 카카오에서 성공했을 수 있다. 이번 -401 은 이번 호출의 실패일 뿐이다(재시도 간격 1분이 지난 뒤)
        given(kakaoUnlinkClient.unlink(anyLong())).willReturn(KakaoUnlinkResult.FAILED);
        lastAttemptAgo(mother, 61);
        withdrawalExecutor.submit(mother.getId()).get(5, TimeUnit.SECONDS);
        assertThat(reload(mother).getWithdrawalStatus()).isEqualTo(WithdrawalStatus.REQUESTED);
        verify(kakaoUnlinkClient, times(2)).unlink(Long.parseLong(mother.getKakaoId()));

        // 두 번 끝나지 않았다 → 2분 뒤
        given(kakaoUnlinkClient.unlink(anyLong())).willReturn(KakaoUnlinkResult.UNLINKED);
        lastAttemptAgo(mother, 121);
        withdrawalExecutor.submit(mother.getId()).get(5, TimeUnit.SECONDS);
        assertThat(reload(mother).isDeleted()).isTrue();
    }

    @Test
    void 처리_중에는_다른_보호_API_를_막고_auth_me_로_진행을_알린다() throws Exception {
        User mother = parent();
        given(kakaoUnlinkClient.unlink(anyLong())).willReturn(KakaoUnlinkResult.UNKNOWN);
        withdrawalService.withdraw(principal(mother), principal(mother));

        mockMvc.perform(get("/api/v1/auth/me").cookie(cookieFor(mother)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.withdrawal").value("in_progress"));
        mockMvc.perform(get("/api/v1/guardians/me/children").cookie(cookieFor(mother)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WITHDRAWAL_IN_PROGRESS"));

        User relogin = userService.findOrCreateByKakaoId(mother.getKakaoId());
        assertThat(relogin.getId()).isEqualTo(mother.getId());
    }

    /**
     * API 로 부르면 요청 동안 영속성 컨텍스트가 열려 있다(open-in-view). 그래도 실행기가 커밋한 결과를 읽어 204 로 끝내야
     * 한다 — 접수 때 읽은 옛 회원을 다시 보면 늘 202 가 된다. 출입증 쿠키는 지우지 않는다. 204 를 놓쳤거나 같은 로그인의 다른
     * 탭이어도 <b>탈퇴에 쓴 그 쿠키로</b> {@code /auth/me} 를 물으면 탈퇴 완료(401 ACCOUNT_WITHDRAWN)를 알 수 있다.
     */
    @Test
    void API_로_탈퇴하면_실행기가_마친_결과를_읽어_204_로_끝낸다() throws Exception {
        User mother = parent();
        Cookie login = cookieFor(mother);

        mockMvc.perform(withCsrf(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/auth/me").param("userId", principal(mother)), login))
                .andExpect(status().isNoContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .stringValues("Set-Cookie", org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.not(org.hamcrest.Matchers.startsWith(JwtCookie.NAME + "=")))));
        assertThat(reload(mother).isDeleted()).isTrue();
        mockMvc.perform(get("/api/v1/auth/me").cookie(login))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_WITHDRAWN"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withCsrf(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, Cookie login)
            throws Exception {
        Cookie csrf = mockMvc.perform(get("/api/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        return request.cookie(login, csrf).header("X-XSRF-TOKEN", csrf.getValue());
    }

    /** 확인 화면을 연 뒤 다른 탭에서 계정을 바꿨다. 화면이 보여 준 계정과 로그인한 계정이 다르면 아무 계정도 지우지 않는다. */
    @Test
    void 확인_화면의_계정과_로그인한_계정이_다르면_409이고_아무것도_바꾸지_않는다() {
        User shown = parent();
        User loggedIn = parent();

        assertThatThrownBy(() -> withdrawalService.withdraw(principal(loggedIn), principal(shown)))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(UserErrorCode.WITHDRAWAL_ACCOUNT_MISMATCH);
        assertThatThrownBy(() -> withdrawalService.withdraw(principal(loggedIn), null))
                .isInstanceOf(UserException.class);
        assertThat(reload(shown).isWithdrawing()).isFalse();
        assertThat(reload(loggedIn).isWithdrawing()).isFalse();
        verify(kakaoUnlinkClient, never()).unlink(anyLong());
    }

    @Test
    void 탈퇴를_열지_않았으면_404이고_접수하지_않는다() {
        User mother = parent();
        WithdrawalService closed = new WithdrawalService(withdrawalSteps, withdrawalExecutor, new WithdrawalProperties(
                false, java.time.Duration.ofSeconds(1), "local", null, null));

        assertThatThrownBy(() -> closed.withdraw(principal(mother), principal(mother)))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(UserErrorCode.WITHDRAWAL_NOT_AVAILABLE);
        assertThat(reload(mother).isWithdrawing()).isFalse();
    }

    /**
     * 실행기가 카카오를 부르는 동안 연결 해제 웹훅이 와서 해제를 확정했다. 그 뒤 실행기가 받은 분명한 오류(첫 호출)로 접수를
     * 취소하면 안 된다 — 카카오가 이미 끊었다고 알려 줬다.
     */
    @Test
    void 카카오_호출_중에_웹훅이_해제를_확정하면_이번_호출의_오류로_취소하지_않는다() throws Exception {
        User mother = parent();
        withdrawalSteps.accept(mother.getId());
        WithdrawalSteps.Plan plan = withdrawalSteps.prepare(mother.getId());
        assertThat(plan.firstAttempt()).isTrue();

        withdrawalSteps.reportUnlinkedByKakao(mother.getKakaoId());
        withdrawalSteps.resolveReport(mother.getId(), KakaoLinkStatus.NOT_LINKED,
                reload(mother).getKakaoUnlinkReportedAt());
        boolean proceed = withdrawalSteps.record(mother.getId(), KakaoUnlinkResult.FAILED, plan.firstAttempt());

        assertThat(proceed).isTrue();
        assertThat(reload(mother).getWithdrawalStatus()).isEqualTo(WithdrawalStatus.KAKAO_UNLINKED);
        withdrawalSteps.cleanup(mother.getId());
        assertThat(reload(mother).isDeleted()).isTrue();
    }

    /**
     * 웹훅 알림을 확인하는 사이 사용자가 카카오로 다시 로그인했다. 로그인이 같은 회원 행 잠금 아래에서 알림을 지우므로, 늦게 온
     * 조회 결과(끊김)로 다시 연결된 회원을 탈퇴시키지 않는다.
     */
    @Test
    void 확인하는_사이_카카오로_다시_로그인하면_이전_조회_결과로_탈퇴시키지_않는다() {
        User mother = parent();
        withdrawalSteps.reportUnlinkedByKakao(mother.getKakaoId());
        WithdrawalSteps.Plan plan = withdrawalSteps.prepare(mother.getId());
        assertThat(plan.kind()).isEqualTo(WithdrawalSteps.Plan.Kind.VERIFY_REPORT);

        // 카카오가 이 회원의 연결을 확인한 것은 알림을 받은 뒤다 — 다시 연결된 것이다
        userService.findOrCreateByKakaoId(mother.getKakaoId(), plan.reportedAt().plusSeconds(1));
        WithdrawalSteps.ReportResolution resolution =
                withdrawalSteps.resolveReport(mother.getId(), KakaoLinkStatus.NOT_LINKED, plan.reportedAt());

        assertThat(resolution).isEqualTo(WithdrawalSteps.ReportResolution.PROCEED);
        User after = reload(mother);
        assertThat(after.isWithdrawing()).isFalse();
        assertThat(after.hasKakaoUnlinkReport()).isFalse();
    }

    /**
     * 카카오가 연결을 확인한 뒤에 사용자가 끊었다(로그인 콜백 처리가 늦어지는 사이 웹훅이 먼저 저장됨). 그 로그인은 알림을 지우지
     * 않는다 — 지우면 끊김 결과도 버려지고 탈퇴할 기록이 사라진다.
     */
    @Test
    void 연결_확인_뒤에_받은_알림은_늦게_끝난_로그인이_지우지_않는다() {
        User mother = parent();
        LocalDateTime verifiedAt = LocalDateTime.now().minusSeconds(5);
        withdrawalSteps.reportUnlinkedByKakao(mother.getKakaoId());

        userService.findOrCreateByKakaoId(mother.getKakaoId(), verifiedAt);

        assertThat(reload(mother).hasKakaoUnlinkReport()).isTrue();
        WithdrawalSteps.Plan plan = withdrawalSteps.prepare(mother.getId());
        assertThat(withdrawalSteps.resolveReport(mother.getId(), KakaoLinkStatus.NOT_LINKED, plan.reportedAt()))
                .isEqualTo(WithdrawalSteps.ReportResolution.PROCEED);
        assertThat(reload(mother).getWithdrawalStatus()).isEqualTo(WithdrawalStatus.KAKAO_UNLINKED);
    }

    /** 개발용 로그인처럼 카카오 확인 시각이 없는 로그인은 알림을 건드리지 않는다. */
    @Test
    void 카카오_확인_시각이_없는_로그인은_알림을_지우지_않는다() {
        User mother = parent();
        withdrawalSteps.reportUnlinkedByKakao(mother.getKakaoId());

        userService.findOrCreateByKakaoId(mother.getKakaoId());

        assertThat(reload(mother).hasKakaoUnlinkReport()).isTrue();
    }

    /**
     * 웹훅으로 시작된 탈퇴를 확정한 뒤 정리 전에 사용자가 카카오로 다시 로그인했다. 확정할 때 웹훅 기록을 지워도 근거(웹훅을 받은
     * 시각)는 남아, 그 뒤 연결을 확인한 로그인이 탈퇴를 취소한다 — 아이 연결과 공유 동의를 지우지 않는다.
     */
    @Test
    void 웹훅_탈퇴를_확정한_뒤_정리_전에_다시_연결한_로그인이_오면_탈퇴를_취소한다() {
        User mother = parent();
        Child child = enrolledChild();
        link(child, mother);
        consent(child, mother);
        withdrawalSteps.reportUnlinkedByKakao(mother.getKakaoId());
        WithdrawalSteps.Plan plan = withdrawalSteps.prepare(mother.getId());
        withdrawalSteps.resolveReport(mother.getId(), KakaoLinkStatus.NOT_LINKED, plan.reportedAt());
        assertThat(reload(mother).getWithdrawalStatus()).isEqualTo(WithdrawalStatus.KAKAO_UNLINKED);
        assertThat(reload(mother).hasKakaoUnlinkReport()).isFalse();

        // 카카오가 이 회원의 연결을 확인한 것은 웹훅을 받은 뒤다 — 그 사이 다시 연결했다
        userService.findOrCreateByKakaoId(mother.getKakaoId(), plan.reportedAt().plusSeconds(1));

        assertThat(withdrawalSteps.cleanup(mother.getId())).isFalse();
        User after = reload(mother);
        assertThat(after.isDeleted()).isFalse();
        assertThat(after.isWithdrawing()).isFalse();
        assertThat(after.getWebhookWithdrawalReportedAt()).isNull();
        assertThat(guardianLink(child, mother).isDeleted()).isFalse();
        assertThat(consentOf(child, mother).isActive()).isTrue();
    }

    /** 웹훅을 받기 전에 연결을 확인한 로그인(콜백 처리가 늦었다)은 웹훅으로 시작된 탈퇴를 취소하지 않는다. */
    @Test
    void 웹훅을_받기_전에_연결을_확인한_로그인은_웹훅_탈퇴를_취소하지_않는다() {
        User mother = parent();
        withdrawalSteps.reportUnlinkedByKakao(mother.getKakaoId());
        WithdrawalSteps.Plan plan = withdrawalSteps.prepare(mother.getId());
        withdrawalSteps.resolveReport(mother.getId(), KakaoLinkStatus.NOT_LINKED, plan.reportedAt());

        userService.findOrCreateByKakaoId(mother.getKakaoId(), plan.reportedAt().minusSeconds(1));

        assertThat(reload(mother).getWithdrawalStatus()).isEqualTo(WithdrawalStatus.KAKAO_UNLINKED);
        assertThat(withdrawalSteps.cleanup(mother.getId())).isTrue();
        assertThat(reload(mother).isDeleted()).isTrue();
    }

    /**
     * 직접 신청한 탈퇴가 카카오 해제를 확정한 뒤 DB 정리가 실패해 다시 기다리는 사이, 사용자가 카카오로 다시 로그인했다. 그 로그인으로
     * 카카오 연결이 다시 생겼을 수 있다 — 탈퇴는 그대로 두고 연결 해제를 다시 부른 뒤 정리한다.
     */
    @Test
    void 직접_신청한_탈퇴의_정리_전에_카카오로_다시_로그인하면_연결_해제를_다시_부르고_탈퇴한다() throws Exception {
        User mother = parent();
        withdrawalSteps.accept(mother.getId());
        WithdrawalSteps.Plan first = withdrawalSteps.prepare(mother.getId());
        withdrawalSteps.record(mother.getId(), KakaoUnlinkResult.UNLINKED, first.firstAttempt());
        assertThat(reload(mother).getWithdrawalStatus()).isEqualTo(WithdrawalStatus.KAKAO_UNLINKED);

        // 정리 전에 다시 로그인 — 탈퇴는 남고 해제 확정만 거둔다
        userService.findOrCreateByKakaoId(mother.getKakaoId(), LocalDateTime.now());
        User reopened = reload(mother);
        assertThat(reopened.getWithdrawalStatus()).isEqualTo(WithdrawalStatus.REQUESTED);
        assertThat(reopened.isDueForRetry(LocalDateTime.now())).isTrue();

        withdrawalExecutor.submit(mother.getId()).get(5, TimeUnit.SECONDS);
        verify(kakaoUnlinkClient).unlink(Long.parseLong(mother.getKakaoId()));
        assertThat(reload(mother).isDeleted()).isTrue();
    }

    /**
     * 웹훅이 직접 신청한 탈퇴의 해제를 확정했다(카카오를 아직 부르지 않음). 그 뒤 다시 로그인하면 연결 해제를 다시 부르는데, 그
     * 호출이 분명한 오류로 끝나도 접수를 취소하지 않는다 — 본인이 탈퇴를 원했고 이미 한 번 끊겼다.
     */
    @Test
    void 웹훅이_확정한_직접_신청_탈퇴는_다시_로그인한_뒤_연결_해제가_실패해도_취소하지_않는다() {
        User mother = parent();
        withdrawalSteps.accept(mother.getId());
        withdrawalSteps.reportUnlinkedByKakao(mother.getKakaoId());
        WithdrawalSteps.Plan plan = withdrawalSteps.prepare(mother.getId());
        withdrawalSteps.resolveReport(mother.getId(), KakaoLinkStatus.NOT_LINKED, plan.reportedAt());
        assertThat(reload(mother).getWebhookWithdrawalReportedAt()).isNull();
        assertThat(reload(mother).getKakaoUnlinkAttemptedAt()).isNull();

        userService.findOrCreateByKakaoId(mother.getKakaoId(), plan.reportedAt().plusSeconds(1));

        assertThat(reload(mother).getWithdrawalStatus()).isEqualTo(WithdrawalStatus.REQUESTED);
        WithdrawalSteps.Plan again = withdrawalSteps.prepare(mother.getId());
        assertThat(again.kind()).isEqualTo(WithdrawalSteps.Plan.Kind.CALL_KAKAO);
        assertThat(again.firstAttempt()).isFalse();
        withdrawalSteps.record(mother.getId(), KakaoUnlinkResult.FAILED, again.firstAttempt());
        assertThat(reload(mother).getWithdrawalStatus()).isEqualTo(WithdrawalStatus.REQUESTED);
    }

    /** 개발용 로그인(카카오 확인 시각 없음)은 카카오 연결과 상관없어 정리만 남은 탈퇴를 건드리지 않는다. */
    @Test
    void 카카오_확인_시각이_없는_로그인은_정리만_남은_탈퇴를_건드리지_않는다() {
        User mother = parent();
        withdrawalSteps.accept(mother.getId());
        WithdrawalSteps.Plan first = withdrawalSteps.prepare(mother.getId());
        withdrawalSteps.record(mother.getId(), KakaoUnlinkResult.UNLINKED, first.firstAttempt());

        userService.findOrCreateByKakaoId(mother.getKakaoId());

        assertThat(reload(mother).getWithdrawalStatus()).isEqualTo(WithdrawalStatus.KAKAO_UNLINKED);
    }

    /**
     * 결과를 모르거나 이전 호출 뒤 거절되면 횟수를 세고, 늘어난 간격이 지나야 다시 부른다. 줄에 서 있던 작업(앞선 호출이 끝나기 전에
     * 주기 작업이 세웠다)도 부르기 직전에 간격을 다시 보므로 건너뛰지 않는다.
     */
    @Test
    void 카카오가_계속_실패하면_재시도_간격이_늘어나고_줄에_서_있던_작업도_간격을_지킨다() {
        User mother = parent();
        withdrawalSteps.accept(mother.getId());
        WithdrawalSteps.Plan first = withdrawalSteps.prepare(mother.getId());
        withdrawalSteps.record(mother.getId(), KakaoUnlinkResult.UNKNOWN, first.firstAttempt());

        // 곧바로 도는 작업은 1분이 지나기 전이라 카카오를 부르지 않는다
        assertThat(withdrawalSteps.prepare(mother.getId()).kind()).isEqualTo(WithdrawalSteps.Plan.Kind.NOTHING);
        assertThat(withdrawalSteps.dueIds()).doesNotContain(mother.getId());

        lastAttemptAgo(mother, 61);
        WithdrawalSteps.Plan second = withdrawalSteps.prepare(mother.getId());
        assertThat(second.kind()).isEqualTo(WithdrawalSteps.Plan.Kind.CALL_KAKAO);
        assertThat(second.firstAttempt()).isFalse();
        withdrawalSteps.record(mother.getId(), KakaoUnlinkResult.FAILED, second.firstAttempt());

        User after = reload(mother);
        assertThat(after.getWithdrawalStatus()).isEqualTo(WithdrawalStatus.REQUESTED);
        assertThat(after.kakaoUnlinkFailureCount()).isEqualTo(2);
        // 두 번 실패 → 마지막 호출 뒤 2분
        assertThat(after.isDueForRetry(after.getKakaoUnlinkAttemptedAt().plusSeconds(90))).isFalse();
        assertThat(after.isDueForRetry(after.getKakaoUnlinkAttemptedAt().plusMinutes(2))).isTrue();
        assertThat(withdrawalSteps.dueIds()).doesNotContain(mother.getId());
    }

    /** 확인하는 사이 새 웹훅이 왔다. 이전 조회 결과(연결됨)로 새 알림을 지우지 않는다. */
    @Test
    void 확인하는_사이_새_웹훅이_오면_이전_조회_결과로_새_알림을_지우지_않는다() throws Exception {
        User mother = parent();
        withdrawalSteps.reportUnlinkedByKakao(mother.getKakaoId());
        WithdrawalSteps.Plan plan = withdrawalSteps.prepare(mother.getId());
        Thread.sleep(5);
        withdrawalSteps.reportUnlinkedByKakao(mother.getKakaoId());

        WithdrawalSteps.ReportResolution resolution =
                withdrawalSteps.resolveReport(mother.getId(), KakaoLinkStatus.LINKED, plan.reportedAt());

        assertThat(resolution).isEqualTo(WithdrawalSteps.ReportResolution.RETRY_LATER);
        assertThat(reload(mother).hasKakaoUnlinkReport()).isTrue();
    }

    // ── 준비 ──────────────────────────────────────────────

    /** 마지막 카카오 호출을 그만큼 앞으로 옮긴다. 재시도 간격이 지난 상태를 만든다. */
    private void lastAttemptAgo(User user, long seconds) {
        transactionTemplate.executeWithoutResult(status -> entityManager
                .createNativeQuery("update users set kakao_unlink_attempted_at = :at where id = :id")
                .setParameter("at", LocalDateTime.now().minusSeconds(seconds))
                .setParameter("id", user.getId())
                .executeUpdate());
    }

    private User parent() {
        User user = persist(UserFixture.parent(nextKakaoId()));
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
