package com.itda.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.itda.backend.domain.Organization;
import com.itda.backend.domain.OrganizationType;
import com.itda.backend.domain.User;
import com.itda.backend.domain.UserRole;
import com.itda.backend.dto.request.SignupRequest;
import com.itda.backend.dto.response.CurrentUserResponse;
import com.itda.backend.exception.OrganizationErrorCode;
import com.itda.backend.exception.OrganizationException;
import com.itda.backend.exception.UserErrorCode;
import com.itda.backend.exception.UserException;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.repository.OrganizationRepository;
import com.itda.backend.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final String KAKAO_ID = "12345";

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, organizationRepository);
    }

    /**
     * 카카오 로그인은 신원 확인만 한다. 역할과 소속은 가입 API 에서 정하므로
     * 첫 로그인에서는 역할 없는(가입 미완료) 회원을 만든다.
     */
    @Test
    void firstLoginCreatesUserWithoutRole() {
        given(userRepository.findByKakaoId(KAKAO_ID)).willReturn(Optional.empty());
        given(userRepository.save(any(User.class))).willAnswer(i -> i.getArgument(0));

        userService.findOrCreateByKakaoId(KAKAO_ID, "박지현");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("박지현");
        assertThat(captor.getValue().getRole()).isNull();
        assertThat(captor.getValue().getOrganizationId()).isNull();
        assertThat(captor.getValue().isSignupCompleted()).isFalse();
    }

    @Test
    void existingUserIsReusedInsteadOfCreatingAnother() {
        User existing = UserFixture.organizationUser(KAKAO_ID, "박지현", 7L);
        given(userRepository.findByKakaoId(KAKAO_ID)).willReturn(Optional.of(existing));

        User found = userService.findOrCreateByKakaoId(KAKAO_ID, "박지현");

        assertThat(found).isSameAs(existing);
        verify(userRepository, never()).save(any(User.class));
    }

    /** 가입을 마친 회원이 다시 로그인해도 역할과 소속은 그대로다. */
    @Test
    void loggingInAgainKeepsTheCompletedSignup() {
        User existing = UserFixture.organizationUser(KAKAO_ID, "박지현", 7L);
        given(userRepository.findByKakaoId(KAKAO_ID)).willReturn(Optional.of(existing));

        User found = userService.findOrCreateByKakaoId(KAKAO_ID, "박지현");

        assertThat(found.getRole()).isEqualTo(UserRole.ORGANIZATION);
        assertThat(found.getOrganizationId()).isEqualTo(7L);
    }

    /** 동의를 철회해 닉네임이 빠져도 이미 저장한 이름을 지우지 않는다. */
    @Test
    void nullNicknameDoesNotWipeTheStoredName() {
        User existing = UserFixture.organizationUser(KAKAO_ID, "박지현", 7L);
        given(userRepository.findByKakaoId(KAKAO_ID)).willReturn(Optional.of(existing));

        User found = userService.findOrCreateByKakaoId(KAKAO_ID, null);

        assertThat(found.getName()).isEqualTo("박지현");
    }

    /** 탈퇴한 사람이 같은 카카오 계정으로 다시 오면 새 행을 만들지 않고 되살린다. */
    @Test
    void withdrawnUserIsRestoredInsteadOfCreatingANewRow() {
        User withdrawn = UserFixture.organizationUser(KAKAO_ID, "박지현", 7L);
        withdrawn.delete();
        given(userRepository.findByKakaoId(KAKAO_ID)).willReturn(Optional.of(withdrawn));

        User found = userService.findOrCreateByKakaoId(KAKAO_ID, "박지현");

        assertThat(found).isSameAs(withdrawn);
        assertThat(found.isDeleted()).isFalse();
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void changedNicknameIsRefreshed() {
        User existing = UserFixture.organizationUser(KAKAO_ID, "옛이름", 7L);
        given(userRepository.findByKakaoId(KAKAO_ID)).willReturn(Optional.of(existing));

        User found = userService.findOrCreateByKakaoId(KAKAO_ID, "새이름");

        assertThat(found.getName()).isEqualTo("새이름");
    }

    @Test
    void currentUserOfOrganizationCarriesLowercaseRoleAndStringIds() {
        given(userRepository.findByIdAndDeletedAtIsNull(1L))
                .willReturn(Optional.of(UserFixture.organizationUser(KAKAO_ID, "박지현", 7L)));

        CurrentUserResponse response = userService.getCurrentUser("1");

        assertThat(response.role()).isEqualTo("org");
        assertThat(response.institutionId()).isEqualTo("7");
        assertThat(response.signupCompleted()).isTrue();
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
                .willThrow(new DataIntegrityViolationException("uk_organization_business_number"));

        assertThatThrownBy(() -> userService.completeSignup("5", ORGANIZATION_SIGNUP))
                .isInstanceOf(OrganizationException.class)
                .extracting(e -> ((OrganizationException) e).getErrorCode())
                .isEqualTo(OrganizationErrorCode.DUPLICATE_BUSINESS_NUMBER);
        assertThat(user.isSignupCompleted()).isFalse();
    }

    @Test
    void signupOfUnknownUserIsReportedAsSessionFailure() {
        given(userRepository.findActiveByIdForUpdate(404L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userService.completeSignup("404", PARENT_SIGNUP))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(UserErrorCode.SESSION_USER_NOT_FOUND);
    }
}
