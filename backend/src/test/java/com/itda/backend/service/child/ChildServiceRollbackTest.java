package com.itda.backend.service.child;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.dto.request.child.RegisterChildRequest;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.service.consent.ConsentService;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.invitation.InvitationService;
import com.itda.backend.service.user.UserService;

@DataJpaTest
@ActiveProfiles("test")
@Import({ChildService.class, ConsentStateReader.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ChildServiceRollbackTest {

    private static final String USER_ID = "u-rollback";
    private static final String CHILD_NAME = "롤백검증아동";

    @Autowired
    private ChildService childService;

    @Autowired
    private ChildRepository childRepository;

    @MockitoBean
    private ChildOrganizationRepository childOrganizationRepository;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private InvitationService invitationService;

    @MockitoBean
    private ConsentService consentService;

    @AfterEach
    void cleanUp() {
        childRepository.deleteAll(childrenNamedForThisTest());
    }

    @Test
    void 기관_연결_저장에_실패하면_아동도_롤백된다() {
        given(userService.getOrganizationIdOf(USER_ID)).willReturn(1L);
        given(childOrganizationRepository.saveAndFlush(any(ChildOrganization.class)))
                .willThrow(new IllegalStateException("연결 저장 실패"));

        assertThatThrownBy(() -> childService.register(USER_ID, new RegisterChildRequest(CHILD_NAME, "2017-03-14", "A-1")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(childrenNamedForThisTest()).isEmpty();
    }

    private List<Child> childrenNamedForThisTest() {
        return childRepository.findAll().stream()
                .filter(child -> CHILD_NAME.equals(child.getName()))
                .toList();
    }
}
