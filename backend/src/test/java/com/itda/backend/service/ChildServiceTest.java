package com.itda.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.itda.backend.domain.Child;
import com.itda.backend.dto.ChildRosterResponse;
import com.itda.backend.repository.ChildRepository;

@ExtendWith(MockitoExtension.class)
class ChildServiceTest {

    private static final String USER_ID = "u-1";
    private static final Long ORG_ID = 1L;

    @Mock
    private ChildRepository childRepository;
    @Mock
    private UserService userService;

    @Test
    void getRoster_mapsChildrenToRosterResponse() {
        ChildService childService = new ChildService(childRepository, userService);
        given(userService.getOrganizationIdOf(USER_ID)).willReturn(ORG_ID);
        given(childRepository.findActiveByOrganizationId(ORG_ID))
                .willReturn(List.of(Child.of("김하늘", LocalDate.of(2020, 1, 1))));

        List<ChildRosterResponse> roster = childService.getRoster(USER_ID);

        assertThat(roster).hasSize(1);
        assertThat(roster.get(0).name()).isEqualTo("김하늘");
        assertThat(roster.get(0).birthDate()).isEqualTo("2020-01-01");
    }
}
