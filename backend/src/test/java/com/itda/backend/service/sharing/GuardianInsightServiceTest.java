package com.itda.backend.service.sharing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import com.itda.backend.domain.approval.Approval;
import com.itda.backend.domain.approval.ApprovalDecision;
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.consent.ConsentRevokeReason;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;
import com.itda.backend.dto.response.guardian.GuardianInsightResponse;
import com.itda.backend.dto.response.organization.InstitutionResponse;
import com.itda.backend.exception.sharing.SharingErrorCode;
import com.itda.backend.exception.sharing.SharingException;
import com.itda.backend.global.config.ClockConfig;
import com.itda.backend.service.user.UserService;

/** 보호자가 Gate 2 를 지나 받은 인사이트를 읽는다 (#233). */
@DataJpaTest
@ActiveProfiles("test")
@Import({GuardianInsightService.class, SharingReadRecorder.class, ClockConfig.class})
class GuardianInsightServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 8, 17);
    private static final LocalDate TO = LocalDate.of(2026, 8, 23);
    private static final String MOTHER = "u-mother";
    private static final String STRANGER = "u-stranger";

    @Autowired
    private TestEntityManager em;

    @Autowired
    private GuardianInsightService service;

    @MockitoBean
    private UserService userService;

    private Long guardianId;
    private Long childId;
    private Long schoolId;
    private Long centerId;
    private Long insightId;
    private Long mineId;
    private Long centerGrantId;

    @BeforeEach
    void setUp() {
        childId = em.persistAndFlush(Child.of("김하늘", LocalDate.of(2018, 3, 2))).getId();
        schoolId = em.persistAndFlush(Organization.of("○○초등학교", OrganizationType.SCHOOL, "1000000001")).getId();
        centerId = em.persistAndFlush(Organization.of("햇살센터", OrganizationType.CENTER, "1000000002")).getId();
        guardianId = 7L;

        insightId = em.persistAndFlush(InsightResult.of(childId, FROM, TO, 1, "급식실 입장 직후",
                "소리를 지름", "이동 전 5분 예고가 효과적이었습니다.", null, "[]", "[]",
                "[%d]".formatted(schoolId), false, null)).getId();
        // Gate 2 승인이 만든 것과 같은 모양 — 보호자 하나, 동의한 기관 하나.
        mineId = em.persistAndFlush(SharingHistory.grant(childId, insightId, schoolId,
                ReceiverType.GUARDIAN, guardianId)).getId();
        centerGrantId = em.persistAndFlush(SharingHistory.grant(childId, insightId, schoolId,
                ReceiverType.ORGANIZATION, centerId)).getId();

        given(userService.getParentIdOf(MOTHER)).willReturn(guardianId);
        given(userService.getParentIdOf(STRANGER)).willReturn(8L);
        em.clear();
    }

    @Test
    void 내게_열린_인사이트만_보인다() {
        assertThat(service.getInsights(MOTHER)).singleElement().satisfies(insight -> {
            assertThat(insight.id()).isEqualTo(String.valueOf(insightId));
            assertThat(insight.from().id()).isEqualTo(String.valueOf(schoolId));
        });
        assertThat(service.getInsights(STRANGER)).isEmpty();
    }

    /** 보호자는 내 아이 글이 어느 기관에 열려 있는지 알아야 한다. 그래야 동의를 끌지 판단한다. */
    @Test
    void 이_글을_볼_수_있는_기관을_보여준다() {
        assertThat(service.getInsights(MOTHER).get(0).sharedWith())
                .extracting(InstitutionResponse::id).containsExactly(String.valueOf(centerId));
    }

    @Test
    void 동의를_꺼서_거둔_기관은_목록에서_빠진다() {
        revoke(centerGrantId, ConsentRevokeReason.GUARDIAN_REVOKED);

        assertThat(service.getInsights(MOTHER).get(0).sharedWith()).isEmpty();
    }

    @Test
    void 내게_열리지_않은_인사이트는_404() {
        assertThatThrownBy(() -> service.getInsight(STRANGER, insightId))
                .isInstanceOf(SharingException.class)
                .extracting(e -> ((SharingException) e).getErrorCode())
                .isEqualTo(SharingErrorCode.NOT_FOUND);
    }

    /** 권한이 거둬진 뒤에는 목록에서도 단건에서도 사라진다 (#214). */
    @Test
    void 내_권한이_거둬지면_더_못_본다() {
        revoke(mineId, ConsentRevokeReason.GUARDIAN_UNLINKED);

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
        LocalDateTime first = service.getInsight(MOTHER, insightId).readAt();
        em.flush();
        em.clear();
        assertThat(first).isNotNull();

        assertThat(service.getInsight(MOTHER, insightId).readAt()).isEqualTo(first);
    }

    // --- 승인 시각 (#287) ---

    @Test
    void 승인_시각을_싣는다() {
        LocalDateTime approvedAt = LocalDateTime.of(2026, 10, 27, 15, 0);
        approve(insightId, approvedAt);

        assertThat(service.getInsights(MOTHER).get(0).approvedAt()).isEqualTo(approvedAt);
        assertThat(service.getInsight(MOTHER, insightId).approvedAt()).isEqualTo(approvedAt);
    }

    /** 순서는 권한 행이 아니라 승인 시각이다. 먼저 만들어졌어도 늦게 승인된 글이 위다. */
    @Test
    void 승인_시각이_최근인_글이_위다() {
        Long later = insight("두 번째 글");
        grantToMother(later);
        approve(insightId, LocalDateTime.of(2026, 10, 28, 9, 0));
        approve(later, LocalDateTime.of(2026, 10, 27, 9, 0));

        assertThat(service.getInsights(MOTHER)).extracting(GuardianInsightResponse::id)
                .containsExactly(String.valueOf(insightId), String.valueOf(later));
    }

    /**
     * 늦게 연결된 보호자에게는 지난 글이 한꺼번에 열려 권한 행이 새 글보다 뒤에 생긴다. 그래도 승인 시각 자리에
     * 놓여야 엄마·아빠 화면이 같다.
     */
    @Test
    void 나중에_열린_지난_글도_승인_시각_자리에_놓인다() {
        // 지난주에 승인됐지만 권한 행은 이번 주 글보다 뒤에 생겼다 — 행 순서로는 지난 글이 위다.
        Long lastWeek = insight("지난주 글");
        grantToMother(lastWeek);
        approve(lastWeek, LocalDateTime.of(2026, 10, 20, 9, 0));
        approve(insightId, LocalDateTime.of(2026, 10, 27, 9, 0));

        assertThat(service.getInsights(MOTHER)).extracting(GuardianInsightResponse::id)
                .containsExactly(String.valueOf(insightId), String.valueOf(lastWeek));
    }

    @Test
    void 승인_기록이_없으면_열린_시각으로_대신한다() {
        LocalDateTime grantedAt = em.find(SharingHistory.class, mineId).getGrantedAt();

        assertThat(service.getInsights(MOTHER).get(0).approvedAt()).isEqualTo(grantedAt);
    }

    private Long insight(String content) {
        Long id = em.persistAndFlush(InsightResult.of(childId, FROM, TO, 2, "상황", "반응", content, null, "[]", "[]",
                "[%d]".formatted(schoolId), false, null)).getId();
        em.clear();
        return id;
    }

    private void grantToMother(Long id) {
        em.persistAndFlush(SharingHistory.grant(childId, id, schoolId, ReceiverType.GUARDIAN, guardianId));
        em.clear();
    }

    /** 승인 기록의 시각은 만들 때 정해진다. 테스트가 순서를 정하려고 덮어쓴다. */
    private void approve(Long id, LocalDateTime at) {
        Approval approval = Approval.gate2(id, 1L, ApprovalDecision.APPROVED);
        ReflectionTestUtils.setField(approval, "reviewedAt", at);
        em.persistAndFlush(approval);
        // 보류 기록은 승인 시각이 아니다 — 섞여 있어도 무시한다.
        em.persistAndFlush(Approval.gate2(id, 1L, ApprovalDecision.HOLD));
        em.clear();
    }

    private void revoke(Long grantId, ConsentRevokeReason reason) {
        em.find(SharingHistory.class, grantId).revoke(reason, LocalDateTime.now());
        em.flush();
        em.clear();
    }
}
