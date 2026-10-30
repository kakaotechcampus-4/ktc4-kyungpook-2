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
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;
import com.itda.backend.dto.response.sharing.InboxDetailResponse;
import com.itda.backend.dto.response.sharing.InboxItemResponse;
import com.itda.backend.exception.sharing.SharingErrorCode;
import com.itda.backend.exception.sharing.SharingException;
import com.itda.backend.global.config.ClockConfig;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.user.UserService;

/**
 * 기관 수신함 (#273). 다른 기관이 Gate 2 에서 승인해 우리 기관에 열린 글을 읽는다.
 *
 * <p>정상 경로는 동의를 끄거나 연결을 끊을 때 권한 줄을 함께 거둔다. 그래서 "읽을 때 다시 본다" 가 혼자서도 막는지는
 * <b>권한 줄을 살려 둔 채</b> 명부·동의·아이만 바꿔서 본다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({InstitutionInboxService.class, SharingReadRecorder.class, ConsentStateReader.class, ClockConfig.class})
class InstitutionInboxServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 8, 17);
    private static final LocalDate TO = LocalDate.of(2026, 8, 23);
    private static final String CENTER_STAFF = "u-center";
    private static final String SCHOOL_STAFF = "u-school";
    private static final Long GUARDIAN_ID = 7L;

    @Autowired
    private TestEntityManager em;

    @Autowired
    private InstitutionInboxService service;

    @MockitoBean
    private UserService userService;

    private Long childId;
    private Long schoolId;
    private Long centerId;
    private Long firstRevisionId;
    private Long secondRevisionId;
    private Long grantId;
    private Long centerConsentId;
    private Long enrollmentId;

    @BeforeEach
    void setUp() {
        childId = em.persistAndFlush(Child.of("김하늘", LocalDate.of(2018, 3, 2))).getId();
        schoolId = em.persistAndFlush(Organization.of("○○초등학교", OrganizationType.SCHOOL, "1000000001")).getId();
        centerId = em.persistAndFlush(Organization.of("햇살센터", OrganizationType.CENTER, "1000000002")).getId();

        em.persist(ChildOrganization.of(childId, schoolId, "S-1"));
        enrollmentId = em.persistAndFlush(ChildOrganization.of(childId, centerId, "C-7")).getId();
        // 학교 동의도 켜 둔다 — 아이 전체 상태가 아니라 "우리 기관 기준" 동의로 판단하는지 보려고.
        em.persist(SharingConsent.grant(childId, GUARDIAN_ID, schoolId, List.of(ConsentField.WEEKLY_INSIGHT)));
        centerConsentId = em.persistAndFlush(
                SharingConsent.grant(childId, GUARDIAN_ID, centerId, List.of(ConsentField.WEEKLY_INSIGHT))).getId();

        firstRevisionId = em.persistAndFlush(insight(1, "이동 전 5분 예고가 효과적이었습니다.")).getId();
        secondRevisionId = em.persistAndFlush(insight(2, "이동 전 그림 카드가 효과적이었습니다.")).getId();
        // Gate 2 승인이 만든 것과 같은 모양 — 학교가 승인해 센터와 보호자에게 열었다.
        grantId = em.persistAndFlush(SharingHistory.grant(childId, firstRevisionId, schoolId,
                ReceiverType.ORGANIZATION, centerId)).getId();
        em.persist(SharingHistory.grant(childId, firstRevisionId, schoolId, ReceiverType.GUARDIAN, GUARDIAN_ID));
        em.persistAndFlush(SharingHistory.grant(childId, secondRevisionId, schoolId,
                ReceiverType.ORGANIZATION, centerId));

        given(userService.getOrganizationIdOf(CENTER_STAFF)).willReturn(centerId);
        given(userService.getOrganizationIdOf(SCHOOL_STAFF)).willReturn(schoolId);
        em.clear();
    }

    @Test
    void 받은_글을_최근_열린_순으로_보여주고_옛_회차도_숨기지_않는다() {
        List<InboxItemResponse> inbox = service.getInbox(CENTER_STAFF);

        assertThat(inbox).extracting(InboxItemResponse::id)
                .containsExactly(String.valueOf(secondRevisionId), String.valueOf(firstRevisionId));
        assertThat(inbox.get(1)).satisfies(item -> {
            assertThat(item.childId()).isEqualTo(String.valueOf(childId));
            assertThat(item.childName()).isEqualTo("김하늘");
            assertThat(item.externalId()).isEqualTo("C-7");
            assertThat(item.from().id()).isEqualTo(String.valueOf(schoolId));
            assertThat(item.period()).isEqualTo("2026년 8월 3주차");
            assertThat(item.receivedAt()).isNotNull();
            assertThat(item.readAt()).isNull();
        });
    }

    /** 승인한 기관 자신은 받지 않는다. 받은 권한이 없는 기관 수신함은 비어 있다. */
    @Test
    void 우리_기관에_열리지_않은_글은_목록에도_단건에도_없다() {
        assertThat(service.getInbox(SCHOOL_STAFF)).isEmpty();
        assertNotFound(SCHOOL_STAFF, firstRevisionId);
        assertNotFound(CENTER_STAFF, 999_999L);
    }

    @Test
    void 단건은_본문을_주고_처음_열어_본_시각을_남긴다() {
        InboxDetailResponse detail = service.getInboxItem(CENTER_STAFF, firstRevisionId);

        assertThat(detail.content()).isEqualTo("이동 전 5분 예고가 효과적이었습니다.");
        assertThat(detail.externalId()).isEqualTo("C-7");
        assertThat(detail.readAt()).isNotNull();
        assertThat(service.getInbox(CENTER_STAFF)).filteredOn(i -> i.id().equals(String.valueOf(firstRevisionId)))
                .singleElement().extracting(InboxItemResponse::readAt).isEqualTo(detail.readAt());
    }

    @Test
    void 다시_열어도_처음_시각을_덮지_않는다() {
        LocalDateTime first = service.getInboxItem(CENTER_STAFF, firstRevisionId).readAt();
        em.clear();

        assertThat(service.getInboxItem(CENTER_STAFF, firstRevisionId).readAt()).isEqualTo(first);
    }

    @Test
    void 거둔_권한은_목록에도_단건에도_없다() {
        em.find(SharingHistory.class, grantId).revoke(ConsentRevokeReason.GUARDIAN_REVOKED, LocalDateTime.now());
        em.flush();
        em.clear();

        assertThat(service.getInbox(CENTER_STAFF)).extracting(InboxItemResponse::id)
                .doesNotContain(String.valueOf(firstRevisionId));
        assertNotFound(CENTER_STAFF, firstRevisionId);
        assertThat(em.find(SharingHistory.class, grantId).getReadAt()).isNull();
    }

    // --- 권한 줄은 살아 있는데 지금 상태가 막는 경우 (읽을 때 판정) ---

    @Test
    void 우리_명부에서_빠진_아이의_글은_권한이_남아_있어도_안_보인다() {
        em.find(ChildOrganization.class, enrollmentId).delete();
        em.flush();
        em.clear();

        assertHiddenButGrantUntouched();
    }

    /** 학교 동의는 켜져 있어 아이 전체 상태는 이용 중이다. 그래도 우리 기관 동의가 꺼졌으면 막는다. */
    @Test
    void 우리_기관_동의만_꺼지면_권한이_남아_있어도_안_보인다() {
        em.find(SharingConsent.class, centerConsentId)
                .revoke(GUARDIAN_ID, ConsentRevokeReason.GUARDIAN_REVOKED, LocalDateTime.now());
        em.flush();
        em.clear();

        assertHiddenButGrantUntouched();
    }

    @Test
    void 삭제된_아이의_글은_권한이_남아_있어도_안_보인다() {
        em.find(Child.class, childId).delete();
        em.flush();
        em.clear();

        assertHiddenButGrantUntouched();
    }

    /** 목록에서 빠지고 단건은 404 다. 조회는 권한을 거두지 않고, 못 연 글에 열람 시각을 남기지도 않는다. */
    private void assertHiddenButGrantUntouched() {
        assertThat(service.getInbox(CENTER_STAFF)).isEmpty();
        assertNotFound(CENTER_STAFF, firstRevisionId);

        em.clear();
        SharingHistory grant = em.find(SharingHistory.class, grantId);
        assertThat(grant.isActive()).isTrue();
        assertThat(grant.getReadAt()).isNull();
    }

    private void assertNotFound(String userId, Long insightId) {
        assertThatThrownBy(() -> service.getInboxItem(userId, insightId))
                .isInstanceOf(SharingException.class)
                .extracting(e -> ((SharingException) e).getErrorCode())
                .isEqualTo(SharingErrorCode.NOT_FOUND);
    }

    private InsightResult insight(int revision, String content) {
        return InsightResult.of(childId, FROM, TO, revision, "급식실 입장 직후", "소리를 지름", content, null,
                "[]", "[]", "[%d]".formatted(schoolId), false, null);
    }
}
