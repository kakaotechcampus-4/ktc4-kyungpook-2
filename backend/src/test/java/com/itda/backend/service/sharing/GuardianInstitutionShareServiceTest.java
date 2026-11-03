package com.itda.backend.service.sharing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.ConsentRevokeReason;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;
import com.itda.backend.dto.response.guardian.InstitutionSharesResponse;
import com.itda.backend.dto.response.guardian.InstitutionSharesResponse.SharedInsight;
import com.itda.backend.exception.child.ChildErrorCode;
import com.itda.backend.exception.child.ChildException;
import com.itda.backend.global.config.ClockConfig;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.user.UserService;

/**
 * 보호자의 기관 공유 내역 (G-45). 거둔 공유도 보여 주고, "지금 볼 수 있음" 은 기관 수신함과 같은 판정이다.
 *
 * <p>센터가 승인한 리포트 두 편을 학교가 받고, 센터 기록으로 만든 한 편은 활동지원사도 받았다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({GuardianInstitutionShareService.class, ConsentStateReader.class, ClockConfig.class})
class GuardianInstitutionShareServiceTest {

    private static final String MOM = "u-mom";
    private static final Long MOM_ID = 7L;
    private static final String STRANGER = "u-stranger";
    private static final Long STRANGER_ID = 8L;

    @Autowired
    private TestEntityManager em;

    @Autowired
    private GuardianInstitutionShareService service;

    @MockitoBean
    private UserService userService;

    private Long childId;
    private Long schoolId;
    private Long centerId;
    private Long assistantId;
    private Long olderId;
    private Long newerId;
    private Long schoolNewerGrantId;
    private Long schoolEnrollmentId;
    private Long schoolConsentId;

    @BeforeEach
    void setUp() {
        childId = em.persistAndFlush(Child.of("김하늘", LocalDate.of(2018, 3, 2))).getId();
        Long otherChildId = em.persistAndFlush(Child.of("이바다", LocalDate.of(2017, 5, 1))).getId();
        schoolId = em.persistAndFlush(Organization.of("하늘초", OrganizationType.SCHOOL, "1000000001")).getId();
        centerId = em.persistAndFlush(Organization.of("바다센터", OrganizationType.CENTER, "1000000002")).getId();
        assistantId = em.persistAndFlush(
                Organization.of("활동지원사 이OO", OrganizationType.ACTIVITY_SUPPORT, "1000000003")).getId();

        em.persist(ChildGuardian.link(childId, MOM_ID, 1L));
        schoolEnrollmentId = em.persistAndFlush(ChildOrganization.of(childId, schoolId, "S-1")).getId();
        em.persist(ChildOrganization.of(childId, centerId, "C-1"));
        em.persist(ChildOrganization.of(childId, assistantId, "A-1"));
        schoolConsentId = em.persistAndFlush(
                SharingConsent.grant(childId, MOM_ID, schoolId, List.of(ConsentField.WEEKLY_INSIGHT))).getId();
        em.persist(SharingConsent.grant(childId, MOM_ID, centerId, List.of(ConsentField.WEEKLY_INSIGHT)));
        em.persist(SharingConsent.grant(childId, MOM_ID, assistantId, List.of(ConsentField.WEEKLY_INSIGHT)));

        olderId = em.persistAndFlush(insight(childId, LocalDate.of(2026, 10, 19))).getId();
        newerId = em.persistAndFlush(insight(childId, LocalDate.of(2026, 10, 26))).getId();
        Long otherChildInsightId = em.persistAndFlush(insight(otherChildId, LocalDate.of(2026, 10, 26))).getId();

        em.persistAndFlush(SharingHistory.grant(childId, olderId, centerId, ReceiverType.ORGANIZATION, schoolId));
        em.persistAndFlush(SharingHistory.grant(childId, olderId, centerId, ReceiverType.ORGANIZATION, assistantId));
        schoolNewerGrantId = em.persistAndFlush(
                SharingHistory.grant(childId, newerId, centerId, ReceiverType.ORGANIZATION, schoolId)).getId();
        // 보호자 줄과 다른 아이 줄은 내역에 나오면 안 된다.
        em.persist(SharingHistory.grant(childId, newerId, centerId, ReceiverType.GUARDIAN, MOM_ID));
        em.persistAndFlush(
                SharingHistory.grant(otherChildId, otherChildInsightId, centerId, ReceiverType.ORGANIZATION, schoolId));

        given(userService.getParentIdOf(MOM)).willReturn(MOM_ID);
        given(userService.getParentIdOf(STRANGER)).willReturn(STRANGER_ID);
        em.clear();
    }

    @Test
    void 기관별로_묶어_이름순으로_주고_묶음_안은_최근_열린_순이다() {
        List<InstitutionSharesResponse> shares = service.getShares(MOM, childId);

        assertThat(shares).extracting(s -> s.institution().name()).containsExactly("하늘초", "활동지원사 이OO");
        InstitutionSharesResponse school = shares.get(0);
        assertThat(school.shares()).extracting(SharedInsight::insightId)
                .containsExactly(String.valueOf(newerId), String.valueOf(olderId));
        assertThat(school.shares().get(0)).satisfies(share -> {
            assertThat(share.period()).isEqualTo("2026년 10월 4주차");
            assertThat(share.from().id()).isEqualTo(String.valueOf(centerId));
            assertThat(share.grantedAt()).isNotNull();
            assertThat(share.visible()).isTrue();
            assertThat(share.hiddenAt()).isNull();
        });
    }

    @Test
    void 다른_아이_줄과_보호자_줄은_나오지_않는다() {
        List<InstitutionSharesResponse> shares = service.getShares(MOM, childId);

        assertThat(shares).flatExtracting(InstitutionSharesResponse::shares).hasSize(3);
        assertThat(shares).extracting(s -> s.institution().id()).doesNotContain(String.valueOf(centerId));
    }

    @Test
    void 거둔_공유는_가려짐과_거둔_시각으로_나온다() {
        LocalDateTime revokedAt = LocalDateTime.of(2026, 11, 2, 9, 30);
        em.find(SharingHistory.class, schoolNewerGrantId).revoke(ConsentRevokeReason.GUARDIAN_REVOKED, revokedAt);
        em.flush();
        em.clear();

        SharedInsight hidden = schoolShares().get(0);

        assertThat(hidden.insightId()).isEqualTo(String.valueOf(newerId));
        assertThat(hidden.visible()).isFalse();
        assertThat(hidden.hiddenAt()).isEqualTo(revokedAt);
        assertThat(schoolShares().get(1).visible()).isTrue();
    }

    /** 권한 줄이 살아 있어도 기관 수신함이 숨기면 보호자 화면도 가려짐이다. 거둔 기록이 없어 시각은 없다. */
    @Test
    void 명부에서_빠진_기관은_권한이_남아_있어도_가려짐이고_시각은_없다() {
        em.find(ChildOrganization.class, schoolEnrollmentId).delete();
        em.flush();
        em.clear();

        assertThat(schoolShares()).allSatisfy(share -> {
            assertThat(share.visible()).isFalse();
            assertThat(share.hiddenAt()).isNull();
        });
    }

    @Test
    void 그_기관_동의만_꺼져도_가려짐이다() {
        em.find(SharingConsent.class, schoolConsentId)
                .revoke(MOM_ID, ConsentRevokeReason.GUARDIAN_REVOKED, LocalDateTime.now());
        em.flush();
        em.clear();

        assertThat(schoolShares()).allSatisfy(share -> assertThat(share.visible()).isFalse());
        assertThat(service.getShares(MOM, childId).get(1).shares())
                .allSatisfy(share -> assertThat(share.visible()).isTrue());
    }

    @Test
    void 연결되지_않은_아이는_403() {
        assertThatThrownBy(() -> service.getShares(STRANGER, childId))
                .isInstanceOf(ChildException.class)
                .extracting(e -> ((ChildException) e).getErrorCode())
                .isEqualTo(ChildErrorCode.CHILD_NOT_LINKED);
    }

    /** 보호자 연결 행은 살아 있는데 아이가 삭제됐다. 연결만 보면 삭제된 아이의 내역이 나간다. */
    @Test
    void 연결은_살아_있어도_삭제된_아이는_403() {
        em.find(Child.class, childId).delete();
        em.flush();
        em.clear();

        assertThatThrownBy(() -> service.getShares(MOM, childId))
                .isInstanceOf(ChildException.class)
                .extracting(e -> ((ChildException) e).getErrorCode())
                .isEqualTo(ChildErrorCode.CHILD_NOT_LINKED);
    }

    private List<SharedInsight> schoolShares() {
        return service.getShares(MOM, childId).stream()
                .filter(s -> s.institution().id().equals(String.valueOf(schoolId)))
                .findFirst().orElseThrow().shares();
    }

    private InsightResult insight(Long child, LocalDate from) {
        return InsightResult.of(child, from, from.plusDays(6), 1, "급식실 입장 직후", "소리를 지름",
                "이동 전 5분 예고가 효과적이었습니다.", null, "[]", "[]", "[%d]".formatted(centerId), false, null);
    }
}
