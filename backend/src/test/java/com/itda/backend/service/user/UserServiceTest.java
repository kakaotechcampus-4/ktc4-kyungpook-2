package com.itda.backend.service.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.domain.user.TermKey;
import com.itda.backend.domain.user.TermsAgreement;
import com.itda.backend.domain.user.User;
import com.itda.backend.domain.user.UserRole;
import com.itda.backend.dto.request.user.SignupRequest;
import com.itda.backend.dto.response.user.CurrentUserResponse;
import com.itda.backend.exception.organization.OrganizationErrorCode;
import com.itda.backend.exception.organization.OrganizationException;
import com.itda.backend.exception.user.UserErrorCode;
import com.itda.backend.exception.user.UserException;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.user.TermsAgreementRepository;
import com.itda.backend.repository.user.UserRepository;
import com.itda.backend.service.invitation.InviteCodeGenerator;
import com.itda.backend.service.organization.OrganizationCodeIssuer;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final String KAKAO_ID = "12345";

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private TermsAgreementRepository termsAgreementRepository;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, organizationRepository,
                new OrganizationCodeIssuer(organizationRepository, new InviteCodeGenerator()),
                new LoginUserTransactionService(userRepository), new TermsService(termsAgreementRepository));
    }

    /**
     * 카카오 로그인은 신원 확인만 한다. 역할과 소속은 가입 API 에서 정하므로
     * 첫 로그인에서는 역할 없는(가입 미완료) 회원을 만든다.
     */
    @Test
    void firstLoginCreatesUserWithoutRole() {
        given(userRepository.findByKakaoIdForUpdate(KAKAO_ID)).willReturn(Optional.empty());
        given(userRepository.saveAndFlush(any(User.class))).willAnswer(i -> i.getArgument(0));

        userService.findOrCreateByKakaoId(KAKAO_ID, "박지현");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("박지현");
        assertThat(captor.getValue().getRole()).isNull();
        assertThat(captor.getValue().getOrganizationId()).isNull();
        assertThat(captor.getValue().isSignupCompleted()).isFalse();
    }

    @Test
    void existingUserIsReusedInsteadOfCreatingAnother() {
        User existing = UserFixture.organizationUser(KAKAO_ID, "박지현", 7L);
        given(userRepository.findByKakaoIdForUpdate(KAKAO_ID)).willReturn(Optional.of(existing));

        User found = userService.findOrCreateByKakaoId(KAKAO_ID, "박지현");

        assertThat(found).isSameAs(existing);
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    /** 가입을 마친 회원이 다시 로그인해도 역할과 소속은 그대로다. */
    @Test
    void loggingInAgainKeepsTheCompletedSignup() {
        User existing = UserFixture.organizationUser(KAKAO_ID, "박지현", 7L);
        given(userRepository.findByKakaoIdForUpdate(KAKAO_ID)).willReturn(Optional.of(existing));

        User found = userService.findOrCreateByKakaoId(KAKAO_ID, "박지현");

        assertThat(found.getRole()).isEqualTo(UserRole.ORGANIZATION);
        assertThat(found.getOrganizationId()).isEqualTo(7L);
    }

    /** 동의를 철회해 닉네임이 빠져도 이미 저장한 이름을 지우지 않는다. */
    @Test
    void nullNicknameDoesNotWipeTheStoredName() {
        User existing = UserFixture.organizationUser(KAKAO_ID, "박지현", 7L);
        given(userRepository.findByKakaoIdForUpdate(KAKAO_ID)).willReturn(Optional.of(existing));

        User found = userService.findOrCreateByKakaoId(KAKAO_ID, null);

        assertThat(found.getName()).isEqualTo("박지현");
    }

    /**
     * 옛 방식으로 탈퇴 표시만 된 행(카카오 회원번호가 남음)으로 다시 오면 되살리지 않는다. 그 행의 식별정보를 지우고 새 회원을
     * 만든다 — 가입 · 약관 동의를 처음부터 한다.
     */
    @Test
    void legacyWithdrawnRowIsErasedAndANewMemberIsCreated() {
        User legacy = UserFixture.legacyDeleted(UserFixture.organizationUser(KAKAO_ID, "박지현", 7L));
        org.springframework.test.util.ReflectionTestUtils.setField(legacy, "id", 5L);
        given(userRepository.findByKakaoIdForUpdate(KAKAO_ID)).willReturn(Optional.of(legacy));
        given(userRepository.saveAndFlush(any(User.class))).willAnswer(invocation -> invocation.getArgument(0));

        User found = userService.findOrCreateByKakaoId(KAKAO_ID, "박지현");

        assertThat(found).isNotSameAs(legacy);
        assertThat(found.getKakaoId()).isEqualTo(KAKAO_ID);
        assertThat(found.isSignupCompleted()).isFalse();
        assertThat(legacy.getKakaoId()).isEqualTo("withdrawn:5");
        assertThat(legacy.getName()).isEqualTo(User.WITHDRAWN_NAME);
        verify(userRepository).flush();
    }

    /** 탈퇴를 처리하는 중이면 새로 만들지도, 이름을 바꾸지도 않는다. */
    @Test
    void withdrawingUserIsReturnedUnchanged() {
        User withdrawing = UserFixture.parent(KAKAO_ID, "옛이름");
        withdrawing.requestWithdrawal(java.time.LocalDateTime.now());
        given(userRepository.findByKakaoIdForUpdate(KAKAO_ID)).willReturn(Optional.of(withdrawing));

        User found = userService.findOrCreateByKakaoId(KAKAO_ID, "새이름");

        assertThat(found).isSameAs(withdrawing);
        assertThat(found.getName()).isEqualTo("옛이름");
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void changedNicknameIsRefreshed() {
        User existing = UserFixture.organizationUser(KAKAO_ID, "옛이름", 7L);
        given(userRepository.findByKakaoIdForUpdate(KAKAO_ID)).willReturn(Optional.of(existing));

        User found = userService.findOrCreateByKakaoId(KAKAO_ID, "새이름");

        assertThat(found.getName()).isEqualTo("새이름");
    }

    @Test
    void currentUserOfOrganizationCarriesLowercaseRoleAndStringIds() {
        given(userRepository.findByIdAndDeletedAtIsNull(1L))
                .willReturn(Optional.of(UserFixture.organizationUser(KAKAO_ID, "박지현", 7L)));
        given(organizationRepository.findById(7L)).willReturn(Optional.of(
                Organization.of("햇살아동발달센터", OrganizationType.CENTER, "1234567890")));

        CurrentUserResponse response = userService.getCurrentUser("1");

        assertThat(response.role()).isEqualTo("org");
        assertThat(response.institutionId()).isEqualTo("7");
        assertThat(response.institutionName()).isEqualTo("햇살아동발달센터");
        assertThat(response.signupCompleted()).isTrue();
    }

    /** 기관 행을 못 찾아도 라우트 가드가 막히지 않게 기관명만 빼고 응답한다. */
    @Test
    void currentUserOfOrganizationWithoutOrganizationRowOmitsOnlyTheName() {
        given(userRepository.findByIdAndDeletedAtIsNull(1L))
                .willReturn(Optional.of(UserFixture.organizationUser(KAKAO_ID, "박지현", 7L)));
        given(organizationRepository.findById(7L)).willReturn(Optional.empty());

        CurrentUserResponse response = userService.getCurrentUser("1");

        assertThat(response.institutionId()).isEqualTo("7");
        assertThat(response.institutionName()).isNull();
    }

    /** 가입 미완료는 정상 상태다. 역할 없이 signupCompleted=false 로 내려 프론트가 가입 화면으로 보낸다. */
    @Test
    void currentUserBeforeSignupHasNoRole() {
        given(userRepository.findByIdAndDeletedAtIsNull(3L))
                .willReturn(Optional.of(User.pending(KAKAO_ID, "박지현")));

        CurrentUserResponse response = userService.getCurrentUser("3");

        assertThat(response.signupCompleted()).isFalse();
        assertThat(response.role()).isNull();
        assertThat(response.institutionId()).isNull();
        assertThat(response.name()).isEqualTo("박지현");
    }

    @Test
    void currentUserOfParentHasNoInstitutionId() {
        given(userRepository.findByIdAndDeletedAtIsNull(2L))
                .willReturn(Optional.of(UserFixture.parent(KAKAO_ID, "김보호")));

        CurrentUserResponse response = userService.getCurrentUser("2");

        assertThat(response.role()).isEqualTo("parent");
        assertThat(response.institutionId()).isNull();
        assertThat(response.institutionName()).isNull();
        verify(organizationRepository, never()).findById(any());
    }

    @Test
    void unknownUserIsReportedAsSessionFailure() {
        given(userRepository.findByIdAndDeletedAtIsNull(404L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getCurrentUser("404"))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(UserErrorCode.SESSION_USER_NOT_FOUND);
    }

    /** subject 전환 전에 발급된 토큰에는 숫자가 아닌 값이 들어 있을 수 있다. */
    @Test
    void nonNumericPrincipalIsReportedAsSessionFailure() {
        assertThatThrownBy(() -> userService.getCurrentUser("kakao-legacy"))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(UserErrorCode.SESSION_USER_NOT_FOUND);
    }

    @Test
    void blankPrincipalIsReportedAsSessionFailure() {
        assertThatThrownBy(() -> userService.getCurrentUser(null))
                .isInstanceOf(UserException.class);
    }

    @Test
    void parentHasNoOrganizationToActOnBehalfOf() {
        given(userRepository.findByIdAndDeletedAtIsNull(2L))
                .willReturn(Optional.of(UserFixture.parent(KAKAO_ID, "김보호")));

        assertThatThrownBy(() -> userService.getOrganizationIdOf("2"))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(UserErrorCode.ORGANIZATION_NOT_ASSIGNED);
    }

    @Test
    void organizationUserResolvesToItsOrganizationId() {
        given(userRepository.findByIdAndDeletedAtIsNull(1L))
                .willReturn(Optional.of(UserFixture.organizationUser(KAKAO_ID, "박지현", 7L)));

        assertThat(userService.getOrganizationIdOf("1")).isEqualTo(7L);
    }

    /* ── 회원가입 ───────────────────────────────────────── */

    private static final SignupRequest PARENT_SIGNUP = new SignupRequest("parent", null, null, null);

    private static final SignupRequest ORGANIZATION_SIGNUP =
            new SignupRequest("org", "햇살아동발달센터", OrganizationType.CENTER, "1234567890");

    private User pendingUserWithId(long id) {
        User user = User.pending(KAKAO_ID, "박지현");
        ReflectionTestUtils.setField(user, "id", id);
        given(userRepository.findActiveByIdForUpdate(id)).willReturn(Optional.of(user));
        return user;
    }

    @Test
    void parentSignupFixesRoleWithoutCreatingAnOrganization() {
        User user = pendingUserWithId(5L);

        CurrentUserResponse response = userService.completeSignup("5", PARENT_SIGNUP);

        assertThat(user.getRole()).isEqualTo(UserRole.PARENT);
        assertThat(response.role()).isEqualTo("parent");
        assertThat(response.signupCompleted()).isTrue();
        assertThat(response.institutionId()).isNull();
        verify(organizationRepository, never()).saveAndFlush(any(Organization.class));
    }

    @Test
    void organizationSignupCreatesTheOrganizationAndJoinsIt() {
        User user = pendingUserWithId(5L);
        given(organizationRepository.existsByBusinessNumber("1234567890")).willReturn(false);
        given(organizationRepository.saveAndFlush(any(Organization.class))).willAnswer(i -> {
            Organization saved = i.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 3L);
            return saved;
        });

        CurrentUserResponse response = userService.completeSignup("5", ORGANIZATION_SIGNUP);

        ArgumentCaptor<Organization> captor = ArgumentCaptor.forClass(Organization.class);
        verify(organizationRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("햇살아동발달센터");
        assertThat(captor.getValue().getType()).isEqualTo(OrganizationType.CENTER);
        assertThat(captor.getValue().getBusinessNumber()).isEqualTo("1234567890");
        assertThat(user.getRole()).isEqualTo(UserRole.ORGANIZATION);
        assertThat(user.getOrganizationId()).isEqualTo(3L);
        assertThat(response.institutionId()).isEqualTo("3");
        assertThat(response.institutionName()).isEqualTo("햇살아동발달센터");
    }

    /** 역할은 한 번 정해지면 바뀌지 않는다. 두 번째 가입 요청은 409 이고 아무것도 만들지 않는다. */
    @Test
    void secondSignupIsRejectedAndChangesNothing() {
        User user = pendingUserWithId(5L);
        user.completeSignup(UserRole.PARENT, null);

        assertThatThrownBy(() -> userService.completeSignup("5", ORGANIZATION_SIGNUP))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(UserErrorCode.ALREADY_SIGNED_UP);
        assertThat(user.getRole()).isEqualTo(UserRole.PARENT);
        verify(organizationRepository, never()).saveAndFlush(any(Organization.class));
    }

    /** 기관 1곳당 계정 1개. 이미 가입된 사업자등록번호면 409 이고 역할도 정하지 않는다. */
    @Test
    void duplicateBusinessNumberIsRejected() {
        User user = pendingUserWithId(5L);
        given(organizationRepository.existsByBusinessNumber("1234567890")).willReturn(true);

        assertThatThrownBy(() -> userService.completeSignup("5", ORGANIZATION_SIGNUP))
                .isInstanceOf(OrganizationException.class)
                .extracting(e -> ((OrganizationException) e).getErrorCode())
                .isEqualTo(OrganizationErrorCode.DUPLICATE_BUSINESS_NUMBER);
        assertThat(user.isSignupCompleted()).isFalse();
        verify(organizationRepository, never()).saveAndFlush(any(Organization.class));
    }

    /**
     * 두 사람이 같은 번호로 동시에 가입하면 둘 다 존재 확인을 통과할 수 있다.
     * 그때는 유니크 제약이 막는데, 그 예외가 500 으로 새지 않고 같은 409 가 되어야 한다.
     */
    @Test
    void duplicateBusinessNumberRaceIsReportedAsConflict() {
        User user = pendingUserWithId(5L);
        given(organizationRepository.existsByBusinessNumber("1234567890")).willReturn(false);
        given(organizationRepository.saveAndFlush(any(Organization.class)))
                .willThrow(violationOf(Organization.UK_BUSINESS_NUMBER));

        assertThatThrownBy(() -> userService.completeSignup("5", ORGANIZATION_SIGNUP))
                .isInstanceOf(OrganizationException.class)
                .extracting(e -> ((OrganizationException) e).getErrorCode())
                .isEqualTo(OrganizationErrorCode.DUPLICATE_BUSINESS_NUMBER);
        assertThat(user.isSignupCompleted()).isFalse();
    }

    /** 가입하면 기관 코드가 함께 붙는다. 보호자가 이 코드로 아이를 이 기관에 연결해 달라고 요청한다. */
    @Test
    void organizationSignupAssignsInstitutionCode() {
        pendingUserWithId(5L);
        given(organizationRepository.saveAndFlush(any(Organization.class))).willAnswer(i -> {
            Organization saved = i.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 3L);
            return saved;
        });

        userService.completeSignup("5", ORGANIZATION_SIGNUP);

        ArgumentCaptor<Organization> captor = ArgumentCaptor.forClass(Organization.class);
        verify(organizationRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getCode()).hasSize(Organization.CODE_LENGTH);
    }

    /** 기관 코드가 동시에 겹쳐 유니크에 걸린 것은 사업자번호 중복이 아니다. 다시 보내면 되는 503 이다. */
    @Test
    void institutionCodeRaceIsNotReportedAsDuplicateBusinessNumber() {
        pendingUserWithId(5L);
        given(organizationRepository.saveAndFlush(any(Organization.class)))
                .willThrow(violationOf(Organization.UK_CODE));

        assertThatThrownBy(() -> userService.completeSignup("5", ORGANIZATION_SIGNUP))
                .isInstanceOf(OrganizationException.class)
                .extracting(e -> ((OrganizationException) e).getErrorCode())
                .isEqualTo(OrganizationErrorCode.INSTITUTION_CODE_ISSUE_FAILED);
    }

    private static DataIntegrityViolationException violationOf(String constraint) {
        return new DataIntegrityViolationException("constraint", new ConstraintViolationException(
                "constraint", new SQLException("duplicate", "23505"), constraint));
    }

    @Test
    void signupOfUnknownUserIsReportedAsSessionFailure() {
        given(userRepository.findActiveByIdForUpdate(404L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userService.completeSignup("404", PARENT_SIGNUP))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(UserErrorCode.SESSION_USER_NOT_FOUND);
    }

    /* ── 가입 · 약관 동의 여부 (가입 미완료 차단 필터용) ───────────── */

    @Test
    void pendingUserIsNotSignedUp() {
        given(userRepository.findByIdAndDeletedAtIsNull(3L))
                .willReturn(Optional.of(User.pending(KAKAO_ID, "박지현")));

        assertThat(userService.accessOf("3")).isEqualTo(AccountAccess.SIGNUP_NOT_COMPLETED);
        verify(termsAgreementRepository, never()).findByUserId(any());
    }

    @Test
    void parentWithoutTermsAgreementIsBlockedByTerms() {
        given(userRepository.findByIdAndDeletedAtIsNull(2L))
                .willReturn(Optional.of(UserFixture.parent(KAKAO_ID, "김보호")));

        assertThat(userService.accessOf("2")).isEqualTo(AccountAccess.TERMS_NOT_AGREED);
    }

    @Test
    void parentWithCurrentTermsAgreementPasses() {
        given(userRepository.findByIdAndDeletedAtIsNull(2L))
                .willReturn(Optional.of(UserFixture.parent(KAKAO_ID, "김보호")));
        given(termsAgreementRepository.findByUserId(any())).willReturn(List.of(
                TermsAgreement.agree(2L, TermKey.TOS, TermsCatalog.currentVersion(TermKey.TOS)),
                TermsAgreement.agree(2L, TermKey.PRIVACY, TermsCatalog.currentVersion(TermKey.PRIVACY))));

        assertThat(userService.accessOf("2")).isEqualTo(AccountAccess.OK);
    }

    /** 기관 담당자도 기관 약관에 동의하기 전에는 막힌다. 보호자 약관 줄로는 통과하지 못한다. */
    @Test
    void organizationUserWithoutOrganizationTermsIsTermsNotAgreed() {
        given(userRepository.findByIdAndDeletedAtIsNull(4L))
                .willReturn(Optional.of(UserFixture.organizationUser(KAKAO_ID, "박지현", 7L)));
        given(termsAgreementRepository.findByUserId(any())).willReturn(List.of(
                TermsAgreement.agree(4L, TermKey.TOS, TermsCatalog.currentVersion(TermKey.TOS)),
                TermsAgreement.agree(4L, TermKey.PRIVACY, TermsCatalog.currentVersion(TermKey.PRIVACY))));

        assertThat(userService.accessOf("4")).isEqualTo(AccountAccess.TERMS_NOT_AGREED);
    }

    @Test
    void organizationUserWithCurrentOrganizationTermsPasses() {
        given(userRepository.findByIdAndDeletedAtIsNull(4L))
                .willReturn(Optional.of(UserFixture.organizationUser(KAKAO_ID, "박지현", 7L)));
        given(termsAgreementRepository.findByUserId(any())).willReturn(List.of(
                TermsAgreement.agree(4L, TermKey.ORG_TOS, TermsCatalog.currentVersion(TermKey.ORG_TOS)),
                TermsAgreement.agree(4L, TermKey.ORG_PRIVACY, TermsCatalog.currentVersion(TermKey.ORG_PRIVACY)),
                TermsAgreement.agree(4L, TermKey.ORG_CHILD_DATA, TermsCatalog.currentVersion(TermKey.ORG_CHILD_DATA))));

        assertThat(userService.accessOf("4")).isEqualTo(AccountAccess.OK);
    }

    @Test
    void unknownUserIsRejectedBeforeSignupCheck() {
        given(userRepository.findByIdAndDeletedAtIsNull(404L)).willReturn(Optional.empty());

        for (String principal : new String[]{"404", "not-a-number", "", "9223372036854775808"}) {
            assertThatThrownBy(() -> userService.accessOf(principal))
                    .isInstanceOfSatisfying(UserException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(UserErrorCode.SESSION_USER_NOT_FOUND));
        }
    }
}
