package com.itda.backend.service.sharing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
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
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;
import com.itda.backend.dto.response.guardian.GuardianInsightResponse;
import com.itda.backend.dto.response.guardian.GuardianInsightResponse.ShareTarget;
import com.itda.backend.exception.sharing.SharingErrorCode;
import com.itda.backend.exception.sharing.SharingException;
import com.itda.backend.repository.sharing.SharingHistoryRepository;
import com.itda.backend.service.user.UserService;

/** 보호자가 인사이트마다 어느 기관에 열어줄지 고른다 (#223). Gate 2 다음 칸이다. */
@DataJpaTest
@ActiveProfiles("test")
@Import(GuardianShareService.class)
class GuardianShareServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 8, 17);
    private static final LocalDate TO = LocalDate.of(2026, 8, 23);
    private static final String MOTHER = "u-mother";
    private static final String STRANGER = "u-stranger";

    @Autowired
    private TestEntityManager em;

    @Autowired
    private GuardianShareService service;

    @Autowired
    private SharingHistoryRepository sharingHistoryRepository;

    @MockitoBean
    private UserService userService;

    private Long childId;
    private Long guardianId;
    private Long schoolId;      // 재료를 낸 기관 = 승인 주체
    private Long centerId;      // 명부 + 동의 → 고를 수 있음
    private Long academyId;     // 명부에만 있고 동의 없음 → 못 고름
    private Long insightId;
    private Long enrollmentId;  // centerId 의 명부 행

    @BeforeEach
    void setUp() {
        childId = em.persistAndFlush(Child.of("김하늘", LocalDate.of(2018, 3, 2))).getId();
        schoolId = em.persistAndFlush(Organization.of("○○초등학교", OrganizationType.SCHOOL, "1000000001")).getId();
        centerId = em.persistAndFlush(Organization.of("햇살센터", OrganizationType.CENTER, "1000000002")).getId();
        academyId = em.persistAndFlush(
                Organization.of("△△활동지원", OrganizationType.ACTIVITY_SUPPORT, "1000000003")).getId();
        guardianId = 7L;

        em.persistAndFlush(ChildOrganization.of(childId, schoolId));
        enrollmentId = em.persistAndFlush(ChildOrganization.of(childId, centerId)).getId();
        em.persistAndFlush(ChildOrganization.of(childId, academyId));
        consent(schoolId);
        consent(centerId);
        // 활동지원사는 명부에만 있고 동의가 없다.

        insightId = em.persistAndFlush(InsightResult.of(childId, FROM, TO, 1, "급식실 입장 직후",
                "소리를 지름", "이동 전 5분 예고가 효과적이었습니다.", null, "[]", "[]",
                "[%d]".formatted(schoolId), false, null)).getId();
        grantToGuardian();

        given(userService.getParentIdOf(MOTHER)).willReturn(guardianId);
        given(userService.getParentIdOf(STRANGER)).willReturn(8L);
        em.clear();
    }

    @Test
    void 내게_열린_인사이트만_보인다() {
        assertThat(service.getInsights(MOTHER)).singleElement()
                .extracting(GuardianInsightResponse::id).isEqualTo(String.valueOf(insightId));
        assertThat(service.getInsights(STRANGER)).isEmpty();
    }

    /** 재료를 낸 기관은 자기 기록으로 만든 글이라 다시 열어줄 필요가 없다. */
    @Test
    void 고를_수_있는_기관은_명부와_동의가_있고_재료를_내지_않은_곳이다() {
        List<ShareTarget> targets = service.getInsights(MOTHER).get(0).sharedWith();

        assertThat(targets).extracting(t -> t.institution().id())
                .containsExactly(String.valueOf(centerId))
                .doesNotContain(String.valueOf(schoolId), String.valueOf(academyId));
        assertThat(targets).extracting(ShareTarget::shared).containsExactly(false);
    }

    @Test
    void 고르면_그_기관이_볼_수_있게_된다() {
        service.share(MOTHER, insightId, List.of(centerId));

        assertThat(activeOrganizationGrants()).containsExactly(centerId);
        assertThat(service.getInsights(MOTHER).get(0).sharedWith())
                .extracting(ShareTarget::shared).containsExactly(true);
    }

    /** 담당자의 Gate 2 승인은 되돌릴 수 없지만, 보호자 선택은 되돌릴 수 있다. */
    @Test
    void 뺀_기관은_꺼지고_다시_넣으면_켜진다() {
        service.share(MOTHER, insightId, List.of(centerId));
        em.flush();
        em.clear();

        service.share(MOTHER, insightId, List.of());
        em.flush();
        em.clear();
        assertThat(activeOrganizationGrants()).isEmpty();

        service.share(MOTHER, insightId, List.of(centerId));
        assertThat(activeOrganizationGrants()).containsExactly(centerId);
    }

    /** 새로 넣으면 (insight, receiver_type, receiver_id) UNIQUE 에 걸린다. 같은 행을 되살려야 한다 (§3.3). */
    @Test
    void 다시_켤_때_행을_새로_만들지_않는다() {
        service.share(MOTHER, insightId, List.of(centerId));
        em.flush();
        em.clear();
        service.share(MOTHER, insightId, List.of());
        em.flush();
        em.clear();

        service.share(MOTHER, insightId, List.of(centerId));
        em.flush();

        assertThat(sharingHistoryRepository.findByInsightResultIdAndReceiverType(
                insightId, ReceiverType.ORGANIZATION)).hasSize(1);
    }

    @Test
    void 같은_요청을_두_번_보내도_결과가_같다() {
        service.share(MOTHER, insightId, List.of(centerId));
        em.flush();
        em.clear();

        service.share(MOTHER, insightId, List.of(centerId));
        em.flush();

        assertThat(activeOrganizationGrants()).containsExactly(centerId);
        assertThat(sharingHistoryRepository.findByInsightResultIdAndReceiverType(
                insightId, ReceiverType.ORGANIZATION)).hasSize(1);
    }

    /** 동의가 없는 기관에 글이 가면 안 된다 (deny-by-default). */
    @Test
    void 동의가_없는_기관은_고를_수_없다() {
        assertThatThrownBy(() -> service.share(MOTHER, insightId, List.of(academyId)))
                .isInstanceOf(SharingException.class)
                .extracting(e -> ((SharingException) e).getErrorCode())
                .isEqualTo(SharingErrorCode.NOT_SHAREABLE);
    }

    /** 하나라도 고를 수 없으면 아무것도 반영하지 않는다. 일부만 켜면 보호자는 전부 켜진 줄 안다. */
    @Test
    void 고를_수_없는_기관이_섞이면_하나도_반영하지_않는다() {
        assertThatThrownBy(() -> service.share(MOTHER, insightId, List.of(centerId, academyId)))
                .isInstanceOf(SharingException.class);

        assertThat(activeOrganizationGrants()).isEmpty();
    }

    /**
     * 명부에서 빠지면 후보가 아니게 되는데, 목록에서도 사라지면 보호자는 아직 켜져 있는 줄 모르고 끌 수도
     * 없다. 명부 이탈은 권한을 거두는 경로가 없어 (동의 철회만 거둔다) 이 화면이 유일한 출구다.
     */
    @Test
    void 켜_둔_기관은_명부에서_빠져도_목록에_남고_끌_수_있다() {
        service.share(MOTHER, insightId, List.of(centerId));
        em.flush();
        em.clear();
        em.find(ChildOrganization.class, enrollmentId).delete();
        em.flush();
        em.clear();

        List<ShareTarget> targets = service.getInsights(MOTHER).get(0).sharedWith();
        assertThat(targets).extracting(t -> t.institution().id()).containsExactly(String.valueOf(centerId));
        assertThat(targets).extracting(ShareTarget::shared).containsExactly(true);

        service.share(MOTHER, insightId, List.of());
        assertThat(activeOrganizationGrants()).isEmpty();
    }

    @Test
    void 내게_열리지_않은_인사이트는_404() {
        assertThatThrownBy(() -> service.share(STRANGER, insightId, List.of(centerId)))
                .isInstanceOf(SharingException.class)
                .extracting(e -> ((SharingException) e).getErrorCode())
                .isEqualTo(SharingErrorCode.NOT_FOUND);
    }

    /** 권한이 거둬진 뒤에는 목록에서도 단건에서도 사라진다 (#213). */
    @Test
    void 내_권한이_거둬지면_더_못_본다() {
        SharingHistory mine = sharingHistoryRepository
                .findByInsightResultIdAndReceiverTypeAndReceiverId(insightId, ReceiverType.GUARDIAN, guardianId)
                .orElseThrow();
        em.find(SharingHistory.class, mine.getId())
                .revoke(ConsentRevokeReason.GUARDIAN_UNLINKED, java.time.LocalDateTime.now());
        em.flush();
        em.clear();

        assertThat(service.getInsights(MOTHER)).isEmpty();
        assertThatThrownBy(() -> service.getInsight(MOTHER, insightId))
                .isInstanceOf(SharingException.class);
    }

    /** 열람 방식에서 "언제 봤나" 를 안 남기면 아무 흔적이 없다 (§9.4). */
    @Test
    void 처음_열어_본_시각을_남기고_다시_열어도_덮지_않는다() {
        assertThat(service.getInsights(MOTHER).get(0).readAt()).isNull();

        service.getInsight(MOTHER, insightId);
        em.flush();
        em.clear();
        // 비교 기준도 DB 에서 읽는다. 메모리 값은 리눅스에서 나노초까지 있어 DB(마이크로초)와 어긋난다.
        var first = service.getInsight(MOTHER, insightId).readAt();
        em.flush();
        em.clear();
        assertThat(first).isNotNull();

        assertThat(service.getInsight(MOTHER, insightId).readAt()).isEqualTo(first);
    }

    private void consent(Long organizationId) {
        em.persistAndFlush(SharingConsent.grant(childId, guardianId, organizationId,
                List.of(ConsentField.WEEKLY_INSIGHT)));
    }

    private void grantToGuardian() {
        em.persistAndFlush(SharingHistory.grant(childId, insightId, schoolId,
                ReceiverType.GUARDIAN, guardianId));
    }

    private List<Long> activeOrganizationGrants() {
        em.flush();
        em.clear();
        return sharingHistoryRepository
                .findByInsightResultIdAndReceiverType(insightId, ReceiverType.ORGANIZATION).stream()
                .filter(SharingHistory::isActive)
                .map(SharingHistory::getReceiverId)
                .toList();
    }
}
