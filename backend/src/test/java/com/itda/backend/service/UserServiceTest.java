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
import org.springframework.test.util.ReflectionTestUtils;

import com.itda.backend.domain.Organization;
import com.itda.backend.domain.OrganizationType;
import com.itda.backend.domain.User;
import com.itda.backend.domain.UserRole;
import com.itda.backend.dto.response.CurrentUserResponse;
import com.itda.backend.exception.UserErrorCode;
import com.itda.backend.exception.UserException;
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

    /** 시드된 기관은 저장된 뒤라 id 가 있다. 저장 전 객체를 쓰면 실제와 다른 상황을 검증하게 된다. */
    private static Organization organization(long id) {
        Organization organization = Organization.of("햇살어린이집", OrganizationType.CENTER);
        ReflectionTestUtils.setField(organization, "id", id);
        return organization;
    }

    @Test
    void firstOrganizationLoginGetsTheFirstSeededOrganization() {
        given(userRepository.findByKakaoId(KAKAO_ID)).willReturn(Optional.empty());
        given(organizationRepository.findFirstByOrderByIdAsc()).willReturn(Optional.of(organization(1L)));
        given(userRepository.save(any(User.class))).willAnswer(i -> i.getArgument(0));

        userService.findOrCreateByKakaoId(KAKAO_ID, "박지현", UserRole.ORGANIZATION);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(UserRole.ORGANIZATION);
        assertThat(captor.getValue().getName()).isEqualTo("박지현");
        assertThat(captor.getValue().getOrganizationId()).isEqualTo(1L);
    }

    @Test
    void parentIsCreatedWithoutOrganization() {
        given(userRepository.findByKakaoId(KAKAO_ID)).willReturn(Optional.empty());
        given(userRepository.save(any(User.class))).willAnswer(i -> i.getArgument(0));

        userService.findOrCreateByKakaoId(KAKAO_ID, "김보호", UserRole.PARENT);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(UserRole.PARENT);
        assertThat(captor.getValue().getOrganizationId()).isNull();
        // 보호자에게는 기관을 찾지도 않는다.
        verify(organizationRepository, never()).findFirstByOrderByIdAsc();
    }

    /**
     * 기관이 하나도 없으면 사용자를 만들지 않는다. 소속 없이 만들어 두면 /auth/me 가
     * role 은 org 인데 institutionId 는 없는 응답을 내보내 계약을 어기고, 역할은 나중에
     * 다시 로그인해도 바뀌지 않아 그 사용자가 영구히 고장난 채로 남는다.
     */
    @Test
    void organizationLoginWithoutAnySeededOrganizationFailsInsteadOfCreatingABrokenUser() {
        given(userRepository.findByKakaoId(KAKAO_ID)).willReturn(Optional.empty());
        given(organizationRepository.findFirstByOrderByIdAsc()).willReturn(Optional.empty());

        assertThatThrownBy(() -> userService.findOrCreateByKakaoId(KAKAO_ID, "박지현", UserRole.ORGANIZATION))
                .isInstanceOf(IllegalStateException.class);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void existingUserIsReusedInsteadOfCreatingAnother() {
        User existing = User.of(KAKAO_ID, "박지현", UserRole.ORGANIZATION, 7L);
        given(userRepository.findByKakaoId(KAKAO_ID)).willReturn(Optional.of(existing));

        User found = userService.findOrCreateByKakaoId(KAKAO_ID, "박지현", UserRole.ORGANIZATION);

        assertThat(found).isSameAs(existing);
        verify(userRepository, never()).save(any(User.class));
    }

    /** 기관으로 가입한 사람이 초대 링크를 눌러도 보호자가 되지 않는다. */
    @Test
    void existingUsersRoleIsNeverOverwritten() {
        User existing = User.of(KAKAO_ID, "박지현", UserRole.ORGANIZATION, 7L);
        given(userRepository.findByKakaoId(KAKAO_ID)).willReturn(Optional.of(existing));

        User found = userService.findOrCreateByKakaoId(KAKAO_ID, "박지현", UserRole.PARENT);

        assertThat(found.getRole()).isEqualTo(UserRole.ORGANIZATION);
        assertThat(found.getOrganizationId()).isEqualTo(7L);
    }

    /** 동의를 철회해 닉네임이 빠져도 이미 저장한 이름을 지우지 않는다. */
    @Test
    void nullNicknameDoesNotWipeTheStoredName() {
        User existing = User.of(KAKAO_ID, "박지현", UserRole.ORGANIZATION, 7L);
        given(userRepository.findByKakaoId(KAKAO_ID)).willReturn(Optional.of(existing));

        User found = userService.findOrCreateByKakaoId(KAKAO_ID, null, UserRole.ORGANIZATION);

        assertThat(found.getName()).isEqualTo("박지현");
    }

    /** 탈퇴한 사람이 같은 카카오 계정으로 다시 오면 새 행을 만들지 않고 되살린다. */
    @Test
    void withdrawnUserIsRestoredInsteadOfCreatingANewRow() {
        User withdrawn = User.of(KAKAO_ID, "박지현", UserRole.ORGANIZATION, 7L);
        withdrawn.delete();
        given(userRepository.findByKakaoId(KAKAO_ID)).willReturn(Optional.of(withdrawn));

        User found = userService.findOrCreateByKakaoId(KAKAO_ID, "박지현", UserRole.ORGANIZATION);

        assertThat(found).isSameAs(withdrawn);
        assertThat(found.isDeleted()).isFalse();
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void changedNicknameIsRefreshed() {
        User existing = User.of(KAKAO_ID, "옛이름", UserRole.ORGANIZATION, 7L);
        given(userRepository.findByKakaoId(KAKAO_ID)).willReturn(Optional.of(existing));

        User found = userService.findOrCreateByKakaoId(KAKAO_ID, "새이름", UserRole.ORGANIZATION);

        assertThat(found.getName()).isEqualTo("새이름");
    }

    @Test
    void currentUserOfOrganizationCarriesLowercaseRoleAndStringIds() {
        given(userRepository.findByIdAndDeletedAtIsNull(1L))
                .willReturn(Optional.of(User.of(KAKAO_ID, "박지현", UserRole.ORGANIZATION, 7L)));

        CurrentUserResponse response = userService.getCurrentUser("1");

        assertThat(response.role()).isEqualTo("org");
        assertThat(response.institutionId()).isEqualTo("7");
    }

    @Test
    void currentUserOfParentHasNoInstitutionId() {
        given(userRepository.findByIdAndDeletedAtIsNull(2L))
                .willReturn(Optional.of(User.of(KAKAO_ID, "김보호", UserRole.PARENT, null)));

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
                .willReturn(Optional.of(User.of(KAKAO_ID, "김보호", UserRole.PARENT, null)));

        assertThatThrownBy(() -> userService.getOrganizationIdOf("2"))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(UserErrorCode.ORGANIZATION_NOT_ASSIGNED);
    }

    @Test
    void organizationUserResolvesToItsOrganizationId() {
        given(userRepository.findByIdAndDeletedAtIsNull(1L))
                .willReturn(Optional.of(User.of(KAKAO_ID, "박지현", UserRole.ORGANIZATION, 7L)));

        assertThat(userService.getOrganizationIdOf("1")).isEqualTo(7L);
    }
}
