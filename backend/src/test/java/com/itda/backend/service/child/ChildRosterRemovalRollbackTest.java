package com.itda.backend.service.child;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.consent.SharingConsentStatus;
import com.itda.backend.domain.invitation.Invitation;
import com.itda.backend.domain.invitation.InvitationStatus;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.fixture.MutableClock;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.consent.SharingConsentRepository;
import com.itda.backend.repository.invitation.InvitationRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.service.consent.ConsentService;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.insight.InsightAccessGranter;
import com.itda.backend.service.insight.InsightAccessRevoker;
import com.itda.backend.service.invitation.InvitationService;
import com.itda.backend.service.invitation.InviteCodeGenerator;
import com.itda.backend.service.user.UserService;

@DataJpaTest
@ActiveProfiles("test")
@Import({ChildService.class, OrganizationChildGuard.class, ConsentService.class, ConsentStateReader.class,
        InsightAccessRevoker.class, InvitationService.class, ChildRosterRemovalRollbackTest.ClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ChildRosterRemovalRollbackTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 9, 10, 0);
    private static final String OUR_STAFF = "u-rollback";

    @TestConfiguration
    static class ClockConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    private ChildService childService;

    @Autowired
    private ChildRepository childRepository;

    @Autowired
    private ChildOrganizationRepository childOrganizationRepository;

    @Autowired
    private InvitationRepository invitationRepository;

    @Autowired
    private SharingConsentRepository sharingConsentRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @MockitoSpyBean
    private InsightAccessRevoker insightAccessRevoker;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private InsightAccessGranter insightAccessGranter;

    @MockitoBean
    private InviteCodeGenerator inviteCodeGenerator;

    private Long childId;
    private Long linkId;
    private Long invitationId;
    private Long consentId;

    @BeforeEach
    void setUp() {
        Organization center = organizationRepository.save(OrganizationFixture.center());
        childId = childRepository.save(Child.of("롤백검증아동", LocalDate.of(2018, 3, 2))).getId();
        linkId = childOrganizationRepository.save(ChildOrganization.of(childId, center.getId(), "R-1")).getId();
        invitationId = invitationRepository.save(
                Invitation.issue(childId, center.getId(), "RLBKCD23", NOW.plusMinutes(5))).getId();
        consentId = sharingConsentRepository.save(
                SharingConsent.grant(childId, 7L, center.getId(), List.of(ConsentField.WEEKLY_INSIGHT))).getId();
        given(userService.getOrganizationIdOf(OUR_STAFF)).willReturn(center.getId());
        given(userService.getUserIdOf(OUR_STAFF)).willReturn(777L);
    }

    @AfterEach
    void cleanUp() {
        sharingConsentRepository.deleteAll();
        invitationRepository.deleteAll();
        childOrganizationRepository.deleteAll();
        childRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void 열람권_회수가_실패하면_명부_코드_동의가_모두_그대로다() {
        willThrow(new IllegalStateException("열람권 회수 실패"))
                .given(insightAccessRevoker).revokeForInstitution(anyLong(), anyLong(), any(), any());

        assertThatThrownBy(() -> childService.removeFromRoster(OUR_STAFF, childId))
                .isInstanceOf(IllegalStateException.class);

        ChildOrganization link = childOrganizationRepository.findById(linkId).orElseThrow();
        assertThat(link.isDeleted()).isFalse();
        assertThat(link.getExternalId()).isEqualTo("R-1");
        assertThat(invitationRepository.findById(invitationId).orElseThrow().getStatus())
                .isEqualTo(InvitationStatus.PENDING);
        assertThat(sharingConsentRepository.findById(consentId).orElseThrow().getStatus())
                .isEqualTo(SharingConsentStatus.ACTIVE);
    }
}
