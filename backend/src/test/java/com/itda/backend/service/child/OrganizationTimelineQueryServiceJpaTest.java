package com.itda.backend.service.child;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
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

import com.itda.backend.domain.approval.Approval;
import com.itda.backend.domain.approval.ApprovalDecision;
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.ConsentRevokeReason;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.domain.summary.ChildContext;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.dto.response.child.OrganizationTimelineEntryResponse;
import com.itda.backend.exception.child.ChildErrorCode;
import com.itda.backend.exception.child.ChildException;
import com.itda.backend.service.user.UserService;

/**
 * 기관 아동 상세의 기록 타임라인 (#292). 명부 권한 확인({@link OrganizationChildGuard})은 진짜를 쓴다 — 명부 제외·아동
 * 삭제가 DB 에서 반영되는지 보려면 가짜로 두면 안 된다. 사용자 조회만 가짜다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({OrganizationTimelineQueryService.class, OrganizationChildGuard.class})
class OrganizationTimelineQueryServiceJpaTest {

    private static final String SCHOOL_STAFF = "u-school";
    private static final LocalDate AUG_20 = LocalDate.of(2026, 8, 20);
    private static final LocalDate AUG_21 = LocalDate.of(2026, 8, 21);

    @Autowired
    private TestEntityManager em;

    @Autowired
    private OrganizationTimelineQueryService service;

    @MockitoBean
    private UserService userService;

    private Long schoolId;
    private Long centerId;
    private Long sky;
    private Long rosterId;

    @BeforeEach
    void setUp() {
        schoolId = em.persistAndFlush(Organization.of("○○초등학교", OrganizationType.SCHOOL, "1000000001")).getId();
        centerId = em.persistAndFlush(Organization.of("햇살센터", OrganizationType.CENTER, "1000000002")).getId();
        sky = em.persistAndFlush(Child.of("김하늘", LocalDate.of(2017, 3, 14))).getId();
        rosterId = em.persistAndFlush(ChildOrganization.of(sky, schoolId, "S-1")).getId();
        em.persistAndFlush(ChildOrganization.of(sky, centerId, "C-1"));
        given(userService.getOrganizationIdOf(SCHOOL_STAFF)).willReturn(schoolId);
        em.clear();
    }

    /** 본문은 담당자가 승인한 최종본이다. 요약의 content 는 AI 원문이라 고쳐 승인한 글이 거기 없다. */
    @Test
    void 본문은_AI_원문이_아니라_승인본이다() {
        SummaryResult summary = summary(sky, AUG_21, schoolId, "AI 가 쓴 원문", "[1, 2]");
        Long contextId = approve(summary, "선생님이 고쳐 승인한 글");
        em.persistAndFlush(Approval.gate1(summary.getId(), 9L, ApprovalDecision.CORRECTED));
        em.clear();

        assertThat(service.timeline(SCHOOL_STAFF, sky)).singleElement().satisfies(entry -> {
            assertThat(entry.id()).isEqualTo(String.valueOf(contextId));
            assertThat(entry.date()).isEqualTo(AUG_21);
            assertThat(entry.content()).isEqualTo("선생님이 고쳐 승인한 글");
            assertThat(entry.edited()).isTrue();
            assertThat(entry.sourceCount()).isEqualTo(2);
        });
    }

    /** 기관 사이에는 Gate 2 를 지난 인사이트로만 넘어간다. 다른 기관의 승인본은 여기 나오지 않는다. */
    @Test
    void 우리_기관이_승인한_요약만_보인다() {
        Long ours = approve(summary(sky, AUG_21, schoolId, "학교 요약", "[1]"), "학교 승인본");
        approve(summary(sky, AUG_21, centerId, "센터 요약", "[2]"), "센터 승인본");
        summary(sky, AUG_20, schoolId, "아직 승인 전", "[3]");
        em.clear();

        assertThat(service.timeline(SCHOOL_STAFF, sky)).extracting(OrganizationTimelineEntryResponse::id)
                .containsExactly(String.valueOf(ours));
    }

    /** 관찰 날짜 최근 순. 승인 뒤 같은 날 일지가 더 오면 새 요약이 따로 승인된다 — 같은 날은 승인 최근 순으로 둘 다 나온다. */
    @Test
    void 관찰_날짜_최근_순이고_같은_날은_승인_최근_순이다() {
        Long first = approve(summary(sky, AUG_21, schoolId, "1판", "[1]", 1), "오전 기록");
        Long older = approve(summary(sky, AUG_20, schoolId, "전날", "[2]"), "전날 기록");
        Long second = approve(summary(sky, AUG_21, schoolId, "2판", "[3]", 2), "오후 기록");
        em.clear();

        assertThat(service.timeline(SCHOOL_STAFF, sky))
                .extracting(OrganizationTimelineEntryResponse::id, OrganizationTimelineEntryResponse::date)
                .containsExactly(
                        tuple(String.valueOf(second), AUG_21),
                        tuple(String.valueOf(first), AUG_21),
                        tuple(String.valueOf(older), AUG_20));
    }

    /** 수정 승인만 표시한다. 다른 기관 요약의 수정 승인 기록이 섞이지 않는다. */
    @Test
    void 고쳐서_승인한_요약만_수정_표시가_붙는다() {
        SummaryResult plain = summary(sky, AUG_20, schoolId, "그대로 승인", "[1]");
        approve(plain, "그대로 승인");
        em.persistAndFlush(Approval.gate1(plain.getId(), 9L, ApprovalDecision.APPROVED));
        SummaryResult center = summary(sky, AUG_21, centerId, "센터", "[2]");
        approve(center, "센터 고친 글");
        em.persistAndFlush(Approval.gate1(center.getId(), 9L, ApprovalDecision.CORRECTED));
        em.clear();

        assertThat(service.timeline(SCHOOL_STAFF, sky)).singleElement()
                .satisfies(entry -> assertThat(entry.edited()).isFalse());
    }

    /** Gate 1 조회와 같은 규칙. 비었거나 배열이 아니거나 깨졌으면 0 — 한 건 때문에 목록 전체가 500 나지 않는다. */
    @Test
    void 원본_건수는_숫자_원소만_세고_읽을_수_없으면_0이다() {
        List<String> broken = java.util.Arrays.asList(null, "", "[]", "{}", "\"3\"", "{깨진 JSON");
        for (int i = 0; i < broken.size(); i++) {
            approve(summary(sky, AUG_21.minusDays(i), schoolId, "요약" + i, broken.get(i)), "승인본" + i);
        }
        approve(summary(sky, AUG_21.plusDays(1), schoolId, "섞임", "[1, \"x\", 2]"), "섞인 배열");
        em.clear();

        assertThat(service.timeline(SCHOOL_STAFF, sky)).extracting(OrganizationTimelineEntryResponse::sourceCount)
                .containsExactly(2, 0, 0, 0, 0, 0, 0);
    }

    @Test
    void 승인본이_없으면_빈_목록이다() {
        assertThat(service.timeline(SCHOOL_STAFF, sky)).isEmpty();
    }

    /** 동의 철회는 다른 기관으로 넘어가는 것과 새 처리를 막는다. 우리 기관이 쓴 지난 기록은 그대로 보인다. */
    @Test
    void 우리_기관_동의가_꺼져도_지난_기록은_보인다() {
        approve(summary(sky, AUG_21, schoolId, "요약", "[1]"), "승인본");
        SharingConsent consent = em.persistAndFlush(
                SharingConsent.grant(sky, 41L, schoolId, List.of(ConsentField.DAILY_SUMMARY)));
        consent.revoke(41L, ConsentRevokeReason.GUARDIAN_REVOKED, java.time.LocalDateTime.now());
        em.flush();
        em.clear();

        assertThat(service.timeline(SCHOOL_STAFF, sky)).hasSize(1);
    }

    // --- 404 — 실제 명부·아이 행을 바꿔서 ---

    @Test
    void 우리_명부에_없는_아이는_404() {
        Long other = em.persistAndFlush(Child.of("이바다", LocalDate.of(2019, 5, 1))).getId();
        em.persistAndFlush(ChildOrganization.of(other, centerId, "C-2"));
        approve(summary(other, AUG_21, centerId, "센터 요약", "[1]"), "센터 승인본");
        em.clear();

        assertNotFound(other);
    }

    @Test
    void 명부에서_빠진_아이는_404() {
        approve(summary(sky, AUG_21, schoolId, "요약", "[1]"), "승인본");
        em.find(ChildOrganization.class, rosterId).delete();
        em.flush();
        em.clear();

        assertNotFound(sky);
    }

    @Test
    void 삭제된_아이는_404() {
        em.find(Child.class, sky).delete();
        em.flush();
        em.clear();

        assertNotFound(sky);
    }

    // --- 도구 ---

    private void assertNotFound(Long childId) {
        assertThatThrownBy(() -> service.timeline(SCHOOL_STAFF, childId))
                .isInstanceOfSatisfying(ChildException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ChildErrorCode.CHILD_NOT_FOUND));
    }

    private SummaryResult summary(Long childId, LocalDate date, Long institutionId, String content, String covered) {
        return summary(childId, date, institutionId, content, covered, 1);
    }

    private SummaryResult summary(Long childId, LocalDate date, Long institutionId, String content, String covered,
            int revision) {
        return em.persistAndFlush(SummaryResult.of(childId, date, institutionId, revision, content, "[]", covered,
                "[]", "{}"));
    }

    /** 담당자가 승인해 승인본을 남긴 것처럼. 승인본 id 를 돌려준다. */
    private Long approve(SummaryResult summary, String approvedContent) {
        return em.persistAndFlush(ChildContext.of(summary.getChildId(), summary.getId(), approvedContent)).getId();
    }
}
