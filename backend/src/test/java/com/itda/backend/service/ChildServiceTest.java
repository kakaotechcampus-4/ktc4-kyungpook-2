package com.itda.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.itda.backend.domain.Child;
import com.itda.backend.domain.ChildOrganization;
import com.itda.backend.domain.ChildStatus;
import com.itda.backend.dto.ChildRosterResponse;
import com.itda.backend.dto.request.RegisterChildRequest;
import com.itda.backend.dto.response.RegisteredChildResponse;
import com.itda.backend.exception.UserErrorCode;
import com.itda.backend.exception.UserException;
import com.itda.backend.repository.ChildOrganizationRepository;
import com.itda.backend.repository.ChildRepository;

@ExtendWith(MockitoExtension.class)
class ChildServiceTest {

    private static final String USER_ID = "u-1";
    private static final Long ORG_ID = 1L;

    @Mock
    private ChildRepository childRepository;
    @Mock
    private ChildOrganizationRepository childOrganizationRepository;
    @Mock
    private UserService userService;

    private ChildService childService;

    @BeforeEach
    void setUp() {
        childService = new ChildService(childRepository, childOrganizationRepository, userService);
    }

    @Test
    void getRoster_mapsChildrenToRosterResponse() {
        given(userService.getOrganizationIdOf(USER_ID)).willReturn(ORG_ID);
        given(childRepository.findByOrganizationId(ORG_ID))
                .willReturn(List.of(Child.of("김하늘", LocalDate.of(2020, 1, 1))));

        List<ChildRosterResponse> roster = childService.getRoster(USER_ID);

        assertThat(roster).hasSize(1);
        assertThat(roster.get(0).name()).isEqualTo("김하늘");
        assertThat(roster.get(0).birthDate()).isEqualTo("2020-01-01");
    }

    @Test
    void register_savesChildAndLinksItToMyOrganization() {
        given(userService.getOrganizationIdOf(USER_ID)).willReturn(ORG_ID);
        given(childRepository.save(any(Child.class))).willAnswer(invocation -> {
            Child child = invocation.getArgument(0);
            ReflectionTestUtils.setField(child, "id", 9L);
            return child;
        });

        RegisteredChildResponse response = childService.register(
                USER_ID, new RegisterChildRequest("김하늘", "2017-03-14"));

        ArgumentCaptor<Child> savedChild = ArgumentCaptor.forClass(Child.class);
        verify(childRepository).save(savedChild.capture());
        assertThat(savedChild.getValue().getName()).isEqualTo("김하늘");
        assertThat(savedChild.getValue().getBirthdate()).isEqualTo(LocalDate.of(2017, 3, 14));

        ArgumentCaptor<ChildOrganization> savedLink = ArgumentCaptor.forClass(ChildOrganization.class);
        verify(childOrganizationRepository).save(savedLink.capture());
        assertThat(savedLink.getValue().getChildId()).isEqualTo(9L);
        assertThat(savedLink.getValue().getOrganizationId()).isEqualTo(ORG_ID);

        assertThat(response.child().id()).isEqualTo("9");
        assertThat(response.child().name()).isEqualTo("김하늘");
        assertThat(response.child().birthDate()).isEqualTo("2017-03-14");
        assertThat(response.child().status()).isEqualTo(ChildStatus.PENDING_CONSENT);
    }

    @Test
    void register_savesNothingWhenUserHasNoOrganization() {
        given(userService.getOrganizationIdOf(USER_ID))
                .willThrow(new UserException(UserErrorCode.ORGANIZATION_NOT_ASSIGNED));

        assertThatThrownBy(() -> childService.register(USER_ID, new RegisterChildRequest("김하늘", "2017-03-14")))
                .isInstanceOf(UserException.class);

        verify(childRepository, never()).save(any());
        verify(childOrganizationRepository, never()).save(any());
    }
}
