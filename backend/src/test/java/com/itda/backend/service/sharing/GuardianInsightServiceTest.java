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
    private Long schoolId;
    private Long centerId;
    private Long insightId;
    private Long mineId;
    private Long centerGrantId;

    @BeforeEach
    void setUp() {
        Long childId = em.persistAndFlush(Child.of("김하늘", LocalDate.of(2018, 3, 2))).getId();
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

    private void revoke(Long grantId, ConsentRevokeReason reason) {
        em.find(SharingHistory.class, grantId).revoke(reason, LocalDateTime.now());
        em.flush();
        em.clear();
    }
}
