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

/**
 * 동의가 꺼지는 두 경로가 실제로 열람 권한까지 거두는지 (#213).
 *
 * <p>{@link InsightAccessRevokerTest} 는 거두는 쪽만 본다. 여기서는 <b>배선</b>을 본다 — 호출이 빠져도
 * 거두는 코드 자체는 멀쩡하므로 단위 테스트만으로는 초록이 된다.
 */
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

        // 학교·센터 기록을 엮은 인사이트가 이미 Gate 2 를 지나 권한이 나간 상태.
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
        // 왜 거뒀는지가 남아야 감사 추적이 된다. 사유를 안 보면 틀린 값이 조용히 나간다.
        assertThat(reload(toCenter).getRevokeReason()).isEqualTo(ConsentRevokeReason.GUARDIAN_REVOKED);
        // 철회한 당사자가 보호자다. 자기 아이 이야기는 계속 본다.
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

    /** 마지막 동의자가 빠지면 그 기관 공유가 꺼진다. 기관 권한도 함께 거둬야 한다. */
    @Test
    void 연결_해제로_기관_공유가_꺼지면_기관_권한도_꺼진다() {
        Long toCenter = grant(ReceiverType.ORGANIZATION, centerId);

        Child locked = em.getEntityManager().find(Child.class, childId);
        consentService.revokeForUnlinkedGuardian(locked, guardianId, guardianId);

        assertThat(reload(toCenter).isActive()).isFalse();
    }

    /**
     * 보호자가 둘인데 한 명만 연결이 끊겼다. 센터에는 다른 보호자의 동의가 <b>남아 있으므로</b> 공유가
     * 꺼지지 않는다 — 끊긴 사람의 동의 행만 보고 거두면 남은 판단을 무시하게 된다.
     *
     * <p>재료가 센터뿐인 인사이트로 본다. 학교 기록이 섞여 있으면 학교 동의가 꺼지면서 통째로 막히는 게
     * 맞아서(여러 기관 claim 을 엮은 결과라 일부만 뺄 수 없다) 이 규칙이 안 보인다.
     */
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

    /** 반대쪽 — 학교 동의가 꺼지면 학교·센터를 엮은 인사이트는 센터 권한까지 통째로 막힌다. */
    @Test
    void 재료_기관_하나가_꺼지면_그_인사이트의_기관_권한은_전부_꺼진다() {
        Long father = 8L;
        em.persistAndFlush(ChildGuardian.link(childId, father, 2L));
        given(userService.getParentIdOf(FATHER)).willReturn(father);
        consentService.update(FATHER, childId, centerId, ConsentAction.GRANT,
                List.of(ConsentField.WEEKLY_INSIGHT));
        Long toCenter = grant(ReceiverType.ORGANIZATION, centerId);

        // 학교 동의자는 엄마뿐이라 연결이 끊기면 학교 공유가 꺼진다.
        Child locked = em.getEntityManager().find(Child.class, childId);
        consentService.revokeForUnlinkedGuardian(locked, guardianId, guardianId);

        assertThat(reload(toCenter).isActive()).isFalse();
    }

    /**
     * Gate 2 승인이 실제로 만드는 모양 (#233) — 재료는 학교만, 받는 쪽은 동의한 센터. 센터가 재료를 내지 않았으니
     * "재료 쪽" 만 보던 철회로는 이 권한이 안 걸린다. 받는 쪽도 봐야 꺼진다.
     */
    @Test
    void 재료를_내지_않고_받기만_한_기관도_동의를_끄면_권한이_꺼진다() {
        Long fromSchoolOnly = em.persistAndFlush(InsightResult.of(childId, FROM, TO, 2, "급식실 입장 직후",
                "소리를 지름", "이동 전 5분 예고가 효과적이었습니다.", null, "[]", "[]",
                "[%d]".formatted(schoolId), false, null)).getId();
        Long toCenter = grant(fromSchoolOnly, ReceiverType.ORGANIZATION, centerId);

        consentService.update(MOTHER, childId, centerId, ConsentAction.REVOKE, List.of());

        assertThat(reload(toCenter).isActive()).isFalse();
    }

    /**
     * 동의는 살려 둔 채 범위에서 주간 인사이트만 빼도 그 기관의 인사이트 권한은 꺼진다 (#257, 멘토님 리뷰 #256 P1).
     * 안 거두면 "하루 요약만" 으로 바꾼 뒤에도 이미 열린 주간 인사이트가 계속 보인다.
     */
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

    /** 주간 인사이트를 남긴 채 범위를 바꾸면 권한은 그대로다. 범위를 바꿀 때마다 거두면 안 된다. */
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
