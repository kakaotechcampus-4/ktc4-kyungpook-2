package com.itda.backend.service.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentAction;
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.ConsentRevokeReason;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;
import com.itda.backend.fixture.MutableClock;
import com.itda.backend.repository.sharing.SharingHistoryRepository;
import com.itda.backend.service.consent.ConsentService;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.user.UserService;

@DataJpaTest
@ActiveProfiles("test")
@Import({ConsentService.class, ConsentStateReader.class, InsightAccessRevoker.class,
        InsightAccessRevocationIntegrationTest.ClockConfig.class})
class InsightAccessRevocationIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 10, 0);
    private static final LocalDate FROM = LocalDate.of(2026, 8, 17);
    private static final LocalDate TO = LocalDate.of(2026, 8, 23);
    private static final String MOTHER = "u-mother";
    private static final String FATHER = "u-father";

    @TestConfiguration
    static class ClockConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    private ConsentService consentService;

    @Autowired
    private TestEntityManager em;

    @Autowired
    private SharingHistoryRepository sharingHistoryRepository;

    @MockitoBean
    private UserService userService;

    private Long childId;
    private Long schoolId;
    private Long centerId;
    private Long guardianId;
    private Long insightId;

    @BeforeEach
    void setUp() {
        childId = em.persistAndFlush(Child.of("김하늘", LocalDate.of(2018, 3, 2))).getId();
        schoolId = em.persistAndFlush(Organization.of("○○초등학교", OrganizationType.SCHOOL, "1000000001")).getId();
        centerId = em.persistAndFlush(Organization.of("햇살센터", OrganizationType.CENTER, "1000000002")).getId();
        em.persistAndFlush(ChildOrganization.of(childId, schoolId));
        em.persistAndFlush(ChildOrganization.of(childId, centerId));
        guardianId = 7L;
        em.persistAndFlush(ChildGuardian.link(childId, guardianId, 1L));
        given(userService.getParentIdOf(MOTHER)).willReturn(guardianId);

        consentService.update(MOTHER, childId, schoolId, ConsentAction.GRANT,
                List.of(ConsentField.WEEKLY_INSIGHT));
        consentService.update(MOTHER, childId, centerId, ConsentAction.GRANT,
                List.of(ConsentField.WEEKLY_INSIGHT));

        insightId = em.persistAndFlush(InsightResult.of(childId, FROM, TO, 1, "급식실 입장 직후",
                "소리를 지름", "이동 전 5분 예고가 효과적이었습니다.", null, "[]", "[]",
                "[%d,%d]".formatted(schoolId, centerId), true, null)).getId();
        em.flush();
        em.clear();
    }

    @Test
    void 보호자가_기관_공유를_중단하면_그_기관의_열람_권한이_꺼진다() {
        Long toCenter = grant(ReceiverType.ORGANIZATION, centerId);
        Long toGuardian = grant(ReceiverType.GUARDIAN, guardianId);

        consentService.update(MOTHER, childId, centerId, ConsentAction.REVOKE, List.of());

        assertThat(reload(toCenter).isActive()).isFalse();
        assertThat(reload(toCenter).getRevokeReason()).isEqualTo(ConsentRevokeReason.GUARDIAN_REVOKED);
        assertThat(reload(toGuardian).isActive()).isTrue();
    }

    @Test
    void 연결이_끊긴_보호자는_더_이상_볼_수_없다() {
        Long toGuardian = grant(ReceiverType.GUARDIAN, guardianId);

        Child locked = em.getEntityManager().find(Child.class, childId);
        consentService.revokeForUnlinkedGuardian(locked, guardianId, guardianId);

        assertThat(reload(toGuardian).isActive()).isFalse();
        assertThat(reload(toGuardian).getRevokeReason()).isEqualTo(ConsentRevokeReason.GUARDIAN_UNLINKED);
    }

    @Test
    void 연결_해제로_기관_공유가_꺼지면_기관_권한도_꺼진다() {
        Long toCenter = grant(ReceiverType.ORGANIZATION, centerId);

        Child locked = em.getEntityManager().find(Child.class, childId);
        consentService.revokeForUnlinkedGuardian(locked, guardianId, guardianId);

        assertThat(reload(toCenter).isActive()).isFalse();
    }

    @Test
    void 다른_보호자의_동의가_남으면_기관_권한은_살아_있다() {
        Long father = 8L;
        em.persistAndFlush(ChildGuardian.link(childId, father, 2L));
        given(userService.getParentIdOf(FATHER)).willReturn(father);
        consentService.update(FATHER, childId, centerId, ConsentAction.GRANT,
                List.of(ConsentField.WEEKLY_INSIGHT));
        Long centerOnly = em.persistAndFlush(InsightResult.of(childId, FROM, TO, 2, "상황", "반응", "본문",
                null, "[]", "[]", "[%d]".formatted(centerId), false, null)).getId();
        Long toCenter = grant(centerOnly, ReceiverType.ORGANIZATION, centerId);

        Child locked = em.getEntityManager().find(Child.class, childId);
        consentService.revokeForUnlinkedGuardian(locked, guardianId, guardianId);

        assertThat(reload(toCenter).isActive()).isTrue();
    }

    @Test
    void 재료_기관_하나가_꺼지면_그_인사이트의_기관_권한은_전부_꺼진다() {
        Long father = 8L;
        em.persistAndFlush(ChildGuardian.link(childId, father, 2L));
        given(userService.getParentIdOf(FATHER)).willReturn(father);
        consentService.update(FATHER, childId, centerId, ConsentAction.GRANT,
                List.of(ConsentField.WEEKLY_INSIGHT));
        Long toCenter = grant(ReceiverType.ORGANIZATION, centerId);

        Child locked = em.getEntityManager().find(Child.class, childId);
        consentService.revokeForUnlinkedGuardian(locked, guardianId, guardianId);

        assertThat(reload(toCenter).isActive()).isFalse();
    }

    @Test
    void 재료를_내지_않고_받기만_한_기관도_동의를_끄면_권한이_꺼진다() {
        Long fromSchoolOnly = em.persistAndFlush(InsightResult.of(childId, FROM, TO, 2, "급식실 입장 직후",
                "소리를 지름", "이동 전 5분 예고가 효과적이었습니다.", null, "[]", "[]",
                "[%d]".formatted(schoolId), false, null)).getId();
        Long toCenter = grant(fromSchoolOnly, ReceiverType.ORGANIZATION, centerId);

        consentService.update(MOTHER, childId, centerId, ConsentAction.REVOKE, List.of());

        assertThat(reload(toCenter).isActive()).isFalse();
    }

    @Test
    void 범위에서_주간_인사이트를_빼면_기관_권한이_꺼진다() {
        Long fromSchoolOnly = em.persistAndFlush(InsightResult.of(childId, FROM, TO, 2, "급식실 입장 직후",
                "소리를 지름", "이동 전 5분 예고가 효과적이었습니다.", null, "[]", "[]",
                "[%d]".formatted(schoolId), false, null)).getId();
        Long toCenter = grant(fromSchoolOnly, ReceiverType.ORGANIZATION, centerId);

        consentService.update(MOTHER, childId, centerId, ConsentAction.GRANT, List.of(ConsentField.DAILY_SUMMARY));

        assertThat(reload(toCenter).isActive()).isFalse();
        assertThat(reload(toCenter).getRevokeReason()).isEqualTo(ConsentRevokeReason.GUARDIAN_REVOKED);
    }

    @Test
    void 주간_인사이트를_남기고_범위를_바꾸면_권한은_그대로다() {
        Long toCenter = grant(ReceiverType.ORGANIZATION, centerId);

        consentService.update(MOTHER, childId, centerId, ConsentAction.GRANT,
                List.of(ConsentField.DAILY_SUMMARY, ConsentField.WEEKLY_INSIGHT));

        assertThat(reload(toCenter).isActive()).isTrue();
    }

    private Long grant(ReceiverType receiverType, Long receiverId) {
        return grant(insightId, receiverType, receiverId);
    }

    private Long grant(Long targetInsightId, ReceiverType receiverType, Long receiverId) {
        Long id = em.persistAndFlush(
                SharingHistory.grant(childId, targetInsightId, schoolId, receiverType, receiverId)).getId();
        em.clear();
        return id;
    }

    private SharingHistory reload(Long id) {
        em.flush();
        em.clear();
        return sharingHistoryRepository.findById(id).orElseThrow();
    }
}
