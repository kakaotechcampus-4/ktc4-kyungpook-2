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

    private static Organization organization() {
        return Organization.of("햇살어린이집", OrganizationType.CENTER);
    }

    @Test
    void firstOrganizationLoginGetsTheFirstSeededOrganization() {
        given(userRepository.findByKakaoId(KAKAO_ID)).willReturn(Optional.empty());
        given(organizationRepository.findFirstByOrderByIdAsc()).willReturn(Optional.of(organization()));
        given(userRepository.save(any(User.class))).willAnswer(i -> i.getArgument(0));

        userService.findOrCreateByKakaoId(KAKAO_ID, "박지현", UserRole.ORGANIZATION);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(UserRole.ORGANIZATION);
        assertThat(captor.getValue().getName()).isEqualTo("박지현");
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

    /** 기관이 하나도 없어도 로그인 자체를 막지는 않는다. 소속 없이 만들고 경고만 남긴다. */
    @Test
    void organizationLoginWithoutAnySeededOrganizationStillCreatesUser() {
        given(userRepository.findByKakaoId(KAKAO_ID)).willReturn(Optional.empty());
        given(organizationRepository.findFirstByOrderByIdAsc()).willReturn(Optional.empty());
        given(userRepository.save(any(User.class))).willAnswer(i -> i.getArgument(0));

        User created = userService.findOrCreateByKakaoId(KAKAO_ID, "박지현", UserRole.ORGANIZATION);

        assertThat(created.getOrganizationId()).isNull();
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

    @Test
    void changedNicknameIsRefreshed() {
        User existing = User.of(KAKAO_ID, "옛이름", UserRole.ORGANIZATION, 7L);
        given(userRepository.findByKakaoId(KAKAO_ID)).willReturn(Optional.of(existing));

        User found = userService.findOrCreateByKakaoId(KAKAO_ID, "새이름", UserRole.ORGANIZATION);

        assertThat(found.getName()).isEqualTo("새이름");
    }

    @Test
    void currentUserOfOrganizationCarriesLowercaseRoleAndStringIds() {
        given(userRepository.findById(1L))
                .willReturn(Optional.of(User.of(KAKAO_ID, "박지현", UserRole.ORGANIZATION, 7L)));

        CurrentUserResponse response = userService.getCurrentUser("1");

        assertThat(response.role()).isEqualTo("org");
        assertThat(response.institutionId()).isEqualTo("7");
    }

    @Test
    void currentUserOfParentHasNoInstitutionId() {
        given(userRepository.findById(2L))
                .willReturn(Optional.of(User.of(KAKAO_ID, "김보호", UserRole.PARENT, null)));

        CurrentUserResponse response = userService.getCurrentUser("2");

        assertThat(response.role()).isEqualTo("parent");
        assertThat(response.institutionId()).isNull();
    }

    @Test
    void unknownUserIsReportedAsSessionFailure() {
        given(userRepository.findById(404L)).willReturn(Optional.empty());

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
        given(userRepository.findById(2L))
                .willReturn(Optional.of(User.of(KAKAO_ID, "김보호", UserRole.PARENT, null)));

        assertThatThrownBy(() -> userService.getOrganizationIdOf("2"))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(UserErrorCode.ORGANIZATION_NOT_ASSIGNED);
    }

    @Test
    void organizationUserResolvesToItsOrganizationId() {
        given(userRepository.findById(1L))
                .willReturn(Optional.of(User.of(KAKAO_ID, "박지현", UserRole.ORGANIZATION, 7L)));

        assertThat(userService.getOrganizationIdOf("1")).isEqualTo(7L);
    }
}
