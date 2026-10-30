package com.itda.backend.service.child;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.domain.summary.ChildContext;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.dto.response.child.GuardianJournalResponse;
import com.itda.backend.dto.response.child.ParentActivityResponse;
import com.itda.backend.exception.child.ChildErrorCode;
import com.itda.backend.exception.child.ChildException;
import com.itda.backend.global.config.ClockConfig;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.invitation.ChildGuardianRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.summary.ChildContextRepository;
import com.itda.backend.repository.summary.SummaryResultRepository;
import com.itda.backend.service.user.UserService;

/**
 * 보호자 일지가 실제 DB 행에서 무엇을 고르는지 본다. 엄마는 하늘이와 연결돼 있고, 하늘이는 센터·학교에 다닌다.
 * 아빠는 바다에게만 연결돼 있다. 일지는 교사가 Gate 1 에서 승인한 요약(승인본) 하나다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({GuardianJournalQueryService.class, ClockConfig.class})
class GuardianJournalQueryServiceJpaTest {

    private static final String MOM_PRINCIPAL = "41";
    private static final String DAD_PRINCIPAL = "52";
    private static final Long MOM = 41L;
    private static final Long DAD = 52L;
    private static final Long INVITATION = 1L;
    private static final LocalDate AUG_20 = LocalDate.of(2026, 8, 20);
    private static final LocalDate AUG_21 = LocalDate.of(2026, 8, 21);

    @Autowired
    private TestEntityManager em;

    @Autowired
    private GuardianJournalQueryService service;

    @Autowired
    private ChildGuardianRepository childGuardianRepository;

    @Autowired
    private ChildRepository childRepository;

    @Autowired
    private ChildContextRepository childContextRepository;

    @Autowired
    private SummaryResultRepository summaryResultRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @MockitoBean
    private UserService userService;

    private Long sky;
    private Long sea;
    private Long centerId;
    private Long schoolId;

    @BeforeEach
    void setUp() {
        sky = em.persistAndFlush(Child.of("김하늘", LocalDate.of(2017, 3, 14))).getId();
        sea = em.persistAndFlush(Child.of("이바다", LocalDate.of(2019, 5, 1))).getId();
        centerId = em.persistAndFlush(Organization.of("햇살센터", OrganizationType.CENTER, "1000000002")).getId();
        schoolId = em.persistAndFlush(Organization.of("○○초등학교", OrganizationType.SCHOOL, "1000000001")).getId();

        em.persistAndFlush(ChildOrganization.of(sky, centerId));
        em.persistAndFlush(ChildOrganization.of(sky, schoolId));
        em.persistAndFlush(ChildOrganization.of(sea, centerId));

        em.persistAndFlush(ChildGuardian.link(sky, MOM, INVITATION));
        em.persistAndFlush(ChildGuardian.link(sea, DAD, INVITATION));

        given(userService.getParentIdOf(MOM_PRINCIPAL)).willReturn(MOM);
        given(userService.getParentIdOf(DAD_PRINCIPAL)).willReturn(DAD);
        em.clear();
    }

    // --- 목록 ---

    /** 관찰 날짜 최근 순이다. 같은 날은 승인 최근 순 — 8월 기록을 늦게 승인해도 날짜 자리에 들어간다. */
    @Test
    void 관찰_날짜_최근_순이고_같은_날은_승인_최근_순이다() {
        Long late = approve(sky, AUG_21, centerId, 1, "21일 센터");
        Long earlier = approve(sky, AUG_20, schoolId, 1, "20일 학교");
        Long sameDayLater = approve(sky, AUG_21, schoolId, 1, "21일 학교");
        em.clear();

        assertThat(service.journal(MOM_PRINCIPAL, sky))
                .extracting(GuardianJournalResponse::id)
                .containsExactly(String.valueOf(sameDayLater), String.valueOf(late), String.valueOf(earlier));
    }

    /** 승인 뒤 같은 날 새 기록이 오면 새 판이 생긴다. 판마다 따로 승인돼 두 건이다. */
    @Test
    void 같은_날_같은_기관의_두_판은_두_건이다() {
        Long first = approve(sky, AUG_21, centerId, 1, "오전 기록");
        Long second = approve(sky, AUG_21, centerId, 2, "오후 기록");
        em.clear();

        assertThat(service.journal(MOM_PRINCIPAL, sky))
                .extracting(GuardianJournalResponse::id, GuardianJournalResponse::content)
                .containsExactly(tuple(String.valueOf(second), "오후 기록"), tuple(String.valueOf(first), "오전 기록"));
    }

    @Test
    void 일지_한_건의_모양() {
        Long id = approve(sky, AUG_21, centerId, 1, "오후 미술 활동에 끝까지 참여했어요.");
        em.clear();

        GuardianJournalResponse entry = service.journal(MOM_PRINCIPAL, sky).getFirst();

        assertThat(entry.id()).isEqualTo(String.valueOf(id));
        assertThat(entry.childId()).isEqualTo(String.valueOf(sky));
        assertThat(entry.institution().id()).isEqualTo(String.valueOf(centerId));
        assertThat(entry.institution().name()).isEqualTo("햇살센터");
        assertThat(entry.institution().type()).isEqualTo("center");
        assertThat(entry.date()).isEqualTo("2026-08-21");
        assertThat(entry.isNew()).isTrue();
        assertThat(entry.content()).isEqualTo("오후 미술 활동에 끝까지 참여했어요.");
    }

    /** 보호자에게는 교사가 승인한 최종본이 간다. 고쳐서 승인했으면 AI 원문이 아니라 고친 글이다. */
    @Test
    void 글은_요약_원문이_아니라_승인본이다() {
        SummaryResult summary = em.persistAndFlush(summary(sky, AUG_21, centerId, 1, "AI 가 쓴 원문"));
        em.persistAndFlush(ChildContext.of(sky, summary.getId(), "교사가 고친 글"));
        em.clear();

        assertThat(service.journal(MOM_PRINCIPAL, sky))
                .extracting(GuardianJournalResponse::content)
                .containsExactly("교사가 고친 글");
    }

    /** 승인되지 않은 요약은 일지가 아니다. 사람이 승인한 글만 보호자에게 간다. */
    @Test
    void 승인되지_않은_요약은_나오지_않는다() {
        em.persistAndFlush(summary(sky, AUG_21, centerId, 1, "아직 검토 전"));
        em.clear();

        assertThat(service.journal(MOM_PRINCIPAL, sky)).isEmpty();
    }

    /** 동의 철회는 다른 기관으로 가는 것을 막는 장치다. 보호자 자신이 지난 일지를 못 보게 하지 않는다. */
    @Test
    void 동의를_끈_기관의_지난_일지도_나온다() {
        approve(sky, AUG_21, centerId, 1, "센터 일지");
        SharingConsent consent = em.persistAndFlush(
                SharingConsent.grant(sky, MOM, centerId, List.of(ConsentField.DAILY_SUMMARY)));
        consent.revoke(MOM, ConsentRevokeReason.GUARDIAN_REVOKED, LocalDateTime.now());
        em.flush();
        em.clear();

        assertThat(service.journal(MOM_PRINCIPAL, sky))
                .extracting(GuardianJournalResponse::content)
                .containsExactly("센터 일지");
    }

    @Test
    void 명부에서_빠진_기관의_지난_일지도_나온다() {
        approve(sky, AUG_21, schoolId, 1, "학교 일지");
        em.getEntityManager()
                .createQuery("select co from ChildOrganization co "
                        + "where co.childId = :childId and co.organizationId = :organizationId", ChildOrganization.class)
                .setParameter("childId", sky)
                .setParameter("organizationId", schoolId)
                .getSingleResult()
                .delete();
        em.flush();
        em.clear();

        assertThat(service.journal(MOM_PRINCIPAL, sky))
                .extracting(journal -> journal.institution().id())
                .containsExactly(String.valueOf(schoolId));
    }

    @Test
    void 다른_아이의_일지는_나오지_않는다() {
        approve(sky, AUG_21, centerId, 1, "하늘이 일지");
        approve(sea, AUG_21, centerId, 1, "바다 일지");
        em.clear();

        assertThat(service.journal(MOM_PRINCIPAL, sky))
                .extracting(GuardianJournalResponse::content)
                .containsExactly("하늘이 일지");
    }

    @Test
    void 승인된_일지가_없으면_빈_목록() {
        assertThat(service.journal(MOM_PRINCIPAL, sky)).isEmpty();
    }

    @Test
    void 연결되지_않은_아이의_목록은_403() {
        assertForbidden(() -> service.journal(MOM_PRINCIPAL, sea));
    }

    @Test
    void 끊긴_연결의_아이_목록은_403() {
        guardianOf(sky, MOM).delete();
        em.flush();
        em.clear();

        assertForbidden(() -> service.journal(MOM_PRINCIPAL, sky));
    }

    @Test
    void 삭제된_아이의_목록은_403() {
        em.find(Child.class, sky).delete();
        em.flush();
        em.clear();

        assertForbidden(() -> service.journal(MOM_PRINCIPAL, sky));
    }

    // --- 새 소식 ---

    /** 승인된 지 24시간 미만이면 새 소식이다. 정각 24시간은 아니다. 읽었는지와는 무관하다. */
    @Test
    void 새_소식은_승인_후_24시간_미만이다() {
        Long id = approve(sky, AUG_21, centerId, 1, "센터 일지");
        em.clear();
        LocalDateTime approvedAt = childContextRepository.findById(id).orElseThrow().getCreatedAt();

        assertThat(isNewAt(approvedAt.plus(Duration.ofHours(24)).minusSeconds(1))).isTrue();
        assertThat(isNewAt(approvedAt.plus(Duration.ofHours(24)))).isFalse();
        assertThat(isNewAt(approvedAt.plus(Duration.ofHours(24)).plusSeconds(1))).isFalse();
    }

    // --- 상세 ---

    @Test
    void 상세는_목록의_한_건과_같다() {
        approve(sky, AUG_20, schoolId, 1, "20일 학교");
        Long id = approve(sky, AUG_21, centerId, 1, "21일 센터");
        em.clear();

        GuardianJournalResponse fromList = service.journal(MOM_PRINCIPAL, sky).getFirst();
        em.clear();

        assertThat(service.entry(MOM_PRINCIPAL, id)).isEqualTo(fromList);
    }

    @Test
    void 없는_일지는_404() {
        assertNotFound(() -> service.entry(MOM_PRINCIPAL, 999_999L));
    }

    /** 남의 아이 일지는 403 이 아니라 404 다. 그 id 의 일지가 있다는 사실도 알리지 않는다. */
    @Test
    void 남의_아이_일지는_404() {
        Long id = approve(sea, AUG_21, centerId, 1, "바다 일지");
        em.clear();

        assertNotFound(() -> service.entry(MOM_PRINCIPAL, id));
    }

    @Test
    void 끊긴_연결의_아이_일지는_404() {
        Long id = approve(sky, AUG_21, centerId, 1, "하늘이 일지");
        guardianOf(sky, MOM).delete();
        em.flush();
        em.clear();

        assertNotFound(() -> service.entry(MOM_PRINCIPAL, id));
    }

    @Test
    void 삭제된_아이의_일지는_404() {
        Long id = approve(sky, AUG_21, centerId, 1, "하늘이 일지");
        em.find(Child.class, sky).delete();
        em.flush();
        em.clear();

        assertNotFound(() -> service.entry(MOM_PRINCIPAL, id));
    }

    // --- 홈 최근 공유 활동 ---

    @Test
    void 활동은_승인된_일지마다_한_줄이다() {
        Long id = approve(sky, AUG_21, centerId, 1, "센터 일지");
        em.clear();
        LocalDateTime approvedAt = childContextRepository.findById(id).orElseThrow().getCreatedAt();

        assertThat(service.recentActivity(sky)).extracting(ActivityLine::line)
                .extracting(ParentActivityResponse::id, ParentActivityResponse::kind, ParentActivityResponse::at,
                        ParentActivityResponse::text, ParentActivityResponse::journalId,
                        ParentActivityResponse::insightId)
                .containsExactly(tuple("journal-" + id, "journal",
                        "%04d-%02d-%02d %02d:%02d".formatted(approvedAt.getYear(), approvedAt.getMonthValue(),
                                approvedAt.getDayOfMonth(), approvedAt.getHour(), approvedAt.getMinute()),
                        "햇살센터에서 8월 21일 일지가 도착했어요",
                        String.valueOf(id), null));
        assertThat(service.recentActivity(sky)).extracting(ActivityLine::at).containsExactly(approvedAt);
    }

    /** 관찰 날짜가 아니라 승인 순이다 — "도착한" 순서다. 최근 5건만이다. */
    @Test
    void 활동은_승인_최근_순_5건이다() {
        List<Long> ids = List.of(
                approve(sky, LocalDate.of(2026, 8, 25), centerId, 1, "1"),
                approve(sky, LocalDate.of(2026, 8, 18), centerId, 1, "2"),
                approve(sky, LocalDate.of(2026, 8, 24), centerId, 1, "3"),
                approve(sky, LocalDate.of(2026, 8, 19), centerId, 1, "4"),
                approve(sky, LocalDate.of(2026, 8, 23), centerId, 1, "5"),
                approve(sky, LocalDate.of(2026, 8, 20), centerId, 1, "6"));
        em.clear();

        assertThat(service.recentActivity(sky)).extracting(ActivityLine::line)
                .extracting(ParentActivityResponse::journalId)
                .containsExactly(ids.reversed().subList(0, 5).stream().map(String::valueOf).toArray(String[]::new));
    }

    // --- 도구 ---

    /** 요약 하나를 만들고 교사가 그대로 승인한 것처럼 승인본을 붙인다. 승인본 id 를 돌려준다. */
    private Long approve(Long childId, LocalDate date, Long institutionId, int revision, String content) {
        SummaryResult summary = em.persistAndFlush(summary(childId, date, institutionId, revision, content));
        return em.persistAndFlush(ChildContext.of(childId, summary.getId(), content)).getId();
    }

    private static SummaryResult summary(Long childId, LocalDate date, Long institutionId, int revision,
            String content) {
        return SummaryResult.of(childId, date, institutionId, revision, content, "[]", "[]", "[]", "{}");
    }

    private boolean isNewAt(LocalDateTime now) {
        Clock clock = Clock.fixed(now.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        GuardianJournalQueryService at = new GuardianJournalQueryService(userService, childGuardianRepository,
                childRepository, childContextRepository, summaryResultRepository, organizationRepository, clock);
        return at.journal(MOM_PRINCIPAL, sky).getFirst().isNew();
    }

    private ChildGuardian guardianOf(Long childId, Long userId) {
        return em.getEntityManager()
                .createQuery("select g from ChildGuardian g where g.childId = :childId and g.userId = :userId",
                        ChildGuardian.class)
                .setParameter("childId", childId)
                .setParameter("userId", userId)
                .getSingleResult();
    }

    private static void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ChildException.class)
                .extracting(e -> ((ChildException) e).getErrorCode())
                .isEqualTo(ChildErrorCode.CHILD_NOT_LINKED);
    }

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ChildException.class)
                .extracting(e -> ((ChildException) e).getErrorCode())
                .isEqualTo(ChildErrorCode.JOURNAL_NOT_FOUND);
    }
}
