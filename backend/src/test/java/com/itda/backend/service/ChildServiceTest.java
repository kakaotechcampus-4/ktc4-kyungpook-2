package com.itda.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.itda.backend.domain.Child;
import com.itda.backend.domain.ChildOrganization;
import com.itda.backend.domain.ChildStatus;
import com.itda.backend.dto.ChildRosterResponse;
import com.itda.backend.dto.request.RegisterChildRequest;
import com.itda.backend.dto.response.RegisteredChildResponse;
import com.itda.backend.exception.ChildErrorCode;
import com.itda.backend.exception.ChildException;
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

    private static Child childWithId(String name, LocalDate birthdate, Long id) {
        Child child = Child.of(name, birthdate);
        ReflectionTestUtils.setField(child, "id", id);
        return child;
    }

    private void givenChildSavedWithId(Long id) {
        given(childRepository.save(any(Child.class))).willAnswer(invocation -> {
            Child child = invocation.getArgument(0);
            ReflectionTestUtils.setField(child, "id", id);
            return child;
        });
    }

    private static DataIntegrityViolationException violationOf(String constraintName) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("duplicate key", new SQLException("23505"), constraintName));
    }

    @Test
    void getRoster_mapsChildrenToRosterResponseWithExternalId() {
        given(userService.getOrganizationIdOf(USER_ID)).willReturn(ORG_ID);
        given(childRepository.findByOrganizationId(ORG_ID)).willReturn(List.of(
                childWithId("김하늘", LocalDate.of(2020, 1, 1), 1L),
                childWithId("임유진", LocalDate.of(2019, 11, 26), 2L)));
        given(childOrganizationRepository.findByOrganizationIdAndDeletedAtIsNull(ORG_ID)).willReturn(List.of(
                ChildOrganization.of(1L, ORG_ID, "2026-0031"),
                ChildOrganization.of(2L, ORG_ID)));

        List<ChildRosterResponse> roster = childService.getRoster(USER_ID);

        assertThat(roster).hasSize(2);
        assertThat(roster.get(0).name()).isEqualTo("김하늘");
        assertThat(roster.get(0).birthDate()).isEqualTo("2020-01-01");
        assertThat(roster.get(0).externalId()).isEqualTo("2026-0031");
        // 관리번호가 없는 아이도 실패하지 않고 null 로 내려간다.
        assertThat(roster.get(1).externalId()).isNull();
    }

    @Test
    void register_savesChildAndLinksItToMyOrganizationWithExternalId() {
        given(userService.getOrganizationIdOf(USER_ID)).willReturn(ORG_ID);
        givenChildSavedWithId(9L);

        RegisteredChildResponse response = childService.register(
                USER_ID, new RegisterChildRequest("김하늘", "2017-03-14", "2026-0031"));

        ArgumentCaptor<Child> savedChild = ArgumentCaptor.forClass(Child.class);
        verify(childRepository).save(savedChild.capture());
        assertThat(savedChild.getValue().getName()).isEqualTo("김하늘");
        assertThat(savedChild.getValue().getBirthdate()).isEqualTo(LocalDate.of(2017, 3, 14));

        ArgumentCaptor<ChildOrganization> savedLink = ArgumentCaptor.forClass(ChildOrganization.class);
        verify(childOrganizationRepository).saveAndFlush(savedLink.capture());
        assertThat(savedLink.getValue().getChildId()).isEqualTo(9L);
        assertThat(savedLink.getValue().getOrganizationId()).isEqualTo(ORG_ID);
        assertThat(savedLink.getValue().getExternalId()).isEqualTo("2026-0031");

        assertThat(response.child().id()).isEqualTo("9");
        assertThat(response.child().name()).isEqualTo("김하늘");
        assertThat(response.child().birthDate()).isEqualTo("2017-03-14");
        assertThat(response.child().externalId()).isEqualTo("2026-0031");
        assertThat(response.child().status()).isEqualTo(ChildStatus.PENDING_CONSENT);
    }

    @Test
    void register_withoutExternalIdSkipsDuplicateCheck() {
        given(userService.getOrganizationIdOf(USER_ID)).willReturn(ORG_ID);
        givenChildSavedWithId(9L);

        RegisteredChildResponse response = childService.register(
                USER_ID, new RegisterChildRequest("김하늘", "2017-03-14", null));

        verify(childOrganizationRepository, never()).existsByOrganizationIdAndExternalId(any(), any());
        assertThat(response.child().externalId()).isNull();
    }

    @Test
    void register_rejectsExternalIdAlreadyUsedInMyOrganizationBeforeSaving() {
        given(userService.getOrganizationIdOf(USER_ID)).willReturn(ORG_ID);
        given(childOrganizationRepository.existsByOrganizationIdAndExternalId(ORG_ID, "2026-0031")).willReturn(true);

        assertThatThrownBy(() -> childService.register(
                USER_ID, new RegisterChildRequest("김하늘", "2017-03-14", "2026-0031")))
                .isInstanceOfSatisfying(ChildException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ChildErrorCode.DUPLICATE_EXTERNAL_ID));

        verify(childRepository, never()).save(any());
        verify(childOrganizationRepository, never()).saveAndFlush(any());
    }

    /** 동시에 같은 번호로 등록해 사전 검사를 함께 통과하면 DB 유니크 제약이 막는다 — 그것도 같은 409 다. */
    @Test
    void register_turnsExternalIdUniqueViolationIntoDuplicateError() {
        given(userService.getOrganizationIdOf(USER_ID)).willReturn(ORG_ID);
        givenChildSavedWithId(9L);
        given(childOrganizationRepository.saveAndFlush(any(ChildOrganization.class)))
                .willThrow(violationOf(ChildOrganization.UK_ORGANIZATION_EXTERNAL_ID));

        assertThatThrownBy(() -> childService.register(
                USER_ID, new RegisterChildRequest("김하늘", "2017-03-14", "2026-0031")))
                .isInstanceOfSatisfying(ChildException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ChildErrorCode.DUPLICATE_EXTERNAL_ID));
    }

    @Test
    void register_doesNotHideOtherConstraintViolations() {
        given(userService.getOrganizationIdOf(USER_ID)).willReturn(ORG_ID);
        givenChildSavedWithId(9L);
        DataIntegrityViolationException other = violationOf("uk_child_organization_child_id_organization_id");
        given(childOrganizationRepository.saveAndFlush(any(ChildOrganization.class))).willThrow(other);

        assertThatThrownBy(() -> childService.register(
                USER_ID, new RegisterChildRequest("김하늘", "2017-03-14", "2026-0031")))
                .isSameAs(other);
    }

    @Test
    void register_savesNothingWhenUserHasNoOrganization() {
        given(userService.getOrganizationIdOf(USER_ID))
                .willThrow(new UserException(UserErrorCode.ORGANIZATION_NOT_ASSIGNED));

        assertThatThrownBy(() -> childService.register(
                USER_ID, new RegisterChildRequest("김하늘", "2017-03-14", null)))
                .isInstanceOf(UserException.class);

        verify(childRepository, never()).save(any());
        verify(childOrganizationRepository, never()).saveAndFlush(any());
    }
}
