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

import com.itda.backend.domain.User;
import com.itda.backend.domain.UserRole;
import com.itda.backend.dto.response.CurrentUserResponse;
import com.itda.backend.exception.UserErrorCode;
import com.itda.backend.exception.UserException;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final String KAKAO_ID = "12345";

    @Mock
    private UserRepository userRepository;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository);
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
}
