package com.itda.backend.service.child;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
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
import com.itda.backend.domain.child.ChildCareInfo;
import com.itda.backend.domain.child.ChildCareInfo.Medication;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.child.ChildStatus;
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.ConsentRevokeReason;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.domain.summary.ChildContext;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.dto.response.child.ChildCareInfoResponse.MedicationResponse;
import com.itda.backend.dto.response.child.GuardianChildResponse;
import com.itda.backend.dto.response.child.GuardianChildResponse.ChildInstitution;
import com.itda.backend.dto.response.child.GuardianHomeResponse;
import com.itda.backend.dto.response.child.ParentActivityResponse;
import com.itda.backend.exception.child.ChildErrorCode;
import com.itda.backend.exception.child.ChildException;
import com.itda.backend.global.config.ClockConfig;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.user.UserService;

/**
 * 보호자 아이 목록·홈이 실제 DB 행에서 무엇을 고르는지 본다. 엄마는 하늘·바다 두 아이와 연결돼 있다.
 * 하늘이는 센터·학교에 다니고, 바다는 센터에만 다닌다. 아빠는 하늘이에게만 연결돼 있다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({GuardianChildQueryService.class, GuardianJournalQueryService.class, ConsentStateReader.class,
        ClockConfig.class})
class GuardianChildQueryServiceJpaTest {

    private static final String MOM_PRINCIPAL = "41";
    private static final String DAD_PRINCIPAL = "52";
    private static final Long MOM = 41L;
    private static final Long DAD = 52L;
    private static final Long INVITATION = 1L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 26, 10, 0);

    @Autowired
    private TestEntityManager em;

    @Autowired
    private GuardianChildQueryService service;

    @MockitoBean
    private UserService userService;

    private Long sea;
    private Long sky;
    private Long centerId;
    private Long schoolId;

    @BeforeEach
    void setUp() {
        // 바다가 먼저 등록돼 id 가 작지만, 엄마는 하늘이와 먼저 연결됐다 — 목록은 연결 순서다.
        sea = em.persistAndFlush(Child.of("이바다", LocalDate.of(2019, 5, 1))).getId();
        sky = em.persistAndFlush(Child.of("김하늘", LocalDate.of(2017, 3, 14))).getId();
        // 센터를 먼저 만들어 기관 id 순서가 센터 → 학교다.
        centerId = em.persistAndFlush(Organization.of("햇살센터", OrganizationType.CENTER, "1000000002")).getId();
        schoolId = em.persistAndFlush(Organization.of("○○초등학교", OrganizationType.SCHOOL, "1000000001")).getId();

        em.persistAndFlush(ChildOrganization.of(sky, schoolId));
        em.persistAndFlush(ChildOrganization.of(sky, centerId));
        em.persistAndFlush(ChildOrganization.of(sea, centerId));

        em.persistAndFlush(ChildGuardian.link(sky, MOM, INVITATION));
        em.persistAndFlush(ChildGuardian.link(sea, MOM, INVITATION));
        em.persistAndFlush(ChildGuardian.link(sky, DAD, INVITATION));

        given(userService.getParentIdOf(MOM_PRINCIPAL)).willReturn(MOM);
        given(userService.getParentIdOf(DAD_PRINCIPAL)).willReturn(DAD);
        em.clear();
    }

    @Test
    void 연결된_아이를_연결된_순서대로_돌려준다() {
        assertThat(service.children(MOM_PRINCIPAL))
                .extracting(GuardianChildResponse::id)
                .containsExactly(String.valueOf(sky), String.valueOf(sea));
    }

    @Test
    void 다른_보호자의_아이는_목록에_없다() {
        assertThat(service.children(DAD_PRINCIPAL))
                .extracting(GuardianChildResponse::id)
                .containsExactly(String.valueOf(sky));
    }

    @Test
    void 연결된_아이가_없으면_빈_목록() {
        given(userService.getParentIdOf("77")).willReturn(77L);

        assertThat(service.children("77")).isEmpty();
    }

    @Test
    void 아이_정보와_기관을_기관_id_순으로_싣는다() {
        GuardianChildResponse child = childOf(service.children(MOM_PRINCIPAL), sky);

        assertThat(child.name()).isEqualTo("김하늘");
        assertThat(child.birthDate()).isEqualTo("2017-03-14");
        assertThat(child.status()).isEqualTo(ChildStatus.PENDING_CONSENT);
        assertThat(child.institutions())
                .extracting(institution -> institution.institution().id(),
                        institution -> institution.institution().type())
                .containsExactly(
                        tuple(String.valueOf(centerId), "center"),
                        tuple(String.valueOf(schoolId), "school"));
        assertThat(child.institutions()).extracting(ChildInstitution::consent)
                .containsOnly(ConsentState.NOT_GRANTED);
    }

    /** 동의 상태는 아이 기준이다. 아빠가 켠 동의도 엄마 화면에 켜짐으로 보인다. */
    @Test
    void 기관별_동의_상태를_아이_기준으로_싣는다() {
        grant(sky, DAD, schoolId);
        revoke(grant(sky, MOM, centerId));
        em.find(Child.class, sky).activate();
        em.flush();
        em.clear();

        GuardianChildResponse child = childOf(service.children(MOM_PRINCIPAL), sky);

        assertThat(child.status()).isEqualTo(ChildStatus.ACTIVE);
        assertThat(consentOf(child, schoolId)).isEqualTo(ConsentState.GRANTED);
        assertThat(consentOf(child, centerId)).isEqualTo(ConsentState.REVOKED);
        // 바다의 센터 동의는 하늘이의 센터 철회와 섞이지 않는다.
        assertThat(consentOf(childOf(service.children(MOM_PRINCIPAL), sea), centerId))
                .isEqualTo(ConsentState.NOT_GRANTED);
    }

    /** 동의를 모두 끈 아이도 넣는다. 설정 화면에서 다시 켜거나 연결을 끊으려면 보여야 한다. */
    @Test
    void 동의를_모두_끈_아이도_목록에_있다() {
        revoke(grant(sea, MOM, centerId));
        em.find(Child.class, sea).suspend();
        em.flush();
        em.clear();

        GuardianChildResponse child = childOf(service.children(MOM_PRINCIPAL), sea);

        assertThat(child.status()).isEqualTo(ChildStatus.SUSPENDED);
        assertThat(consentOf(child, centerId)).isEqualTo(ConsentState.REVOKED);
    }

    @Test
    void 끊긴_연결의_아이는_빠진다() {
        guardianOf(sea, MOM).delete();
        em.flush();
        em.clear();

        assertThat(service.children(MOM_PRINCIPAL))
                .extracting(GuardianChildResponse::id)
                .containsExactly(String.valueOf(sky));
    }

    @Test
    void 삭제된_아이는_빠진다() {
        em.find(Child.class, sea).delete();
        em.flush();
        em.clear();

        assertThat(service.children(MOM_PRINCIPAL))
                .extracting(GuardianChildResponse::id)
                .containsExactly(String.valueOf(sky));
    }

    @Test
    void 명부에서_빠진_기관은_싣지_않는다() {
        em.getEntityManager()
                .createQuery("select co from ChildOrganization co "
                        + "where co.childId = :childId and co.organizationId = :organizationId", ChildOrganization.class)
                .setParameter("childId", sky)
                .setParameter("organizationId", schoolId)
                .getSingleResult()
                .delete();
        em.flush();
        em.clear();

        assertThat(childOf(service.children(MOM_PRINCIPAL), sky).institutions())
                .extracting(institution -> institution.institution().id())
                .containsExactly(String.valueOf(centerId));
    }

    /** 이름을 모르는 기관을 화면에 올리지 않는다. */
    @Test
    void 기관_행이_없는_명부_항목은_싣지_않는다() {
        em.persistAndFlush(ChildOrganization.of(sea, 999_999L));
        em.clear();

        assertThat(childOf(service.children(MOM_PRINCIPAL), sea).institutions())
                .extracting(institution -> institution.institution().id())
                .containsExactly(String.valueOf(centerId));
    }

    // --- 돌봄 정보 ---

    @Test
    void 저장한_돌봄_정보를_아이마다_싣는다() {
        em.persistAndFlush(ChildCareInfo.of(sky, List.of("땅콩"), List.of(new Medication("○○정", "점심 식후")), DAD));
        em.clear();

        List<GuardianChildResponse> children = service.children(MOM_PRINCIPAL);

        assertThat(childOf(children, sky).care().allergies()).containsExactly("땅콩");
        assertThat(childOf(children, sky).care().medications())
                .extracting(MedicationResponse::name, MedicationResponse::time)
                .containsExactly(tuple("○○정", "점심 식후"));
        // 저장한 적이 없는 아이는 빈 배열 둘이다
        assertThat(childOf(children, sea).care().allergies()).isEmpty();
        assertThat(childOf(children, sea).care().medications()).isEmpty();
    }

    @Test
    void 홈에도_돌봄_정보가_실린다() {
        em.persistAndFlush(ChildCareInfo.of(sea, List.of("계란"), List.of(), MOM));
        em.clear();

        assertThat(service.home(MOM_PRINCIPAL, sea).child().care().allergies()).containsExactly("계란");
    }

    // --- 홈 ---

    @Test
    void 홈은_고른_아이를_돌려주고_승인된_일지가_없으면_활동은_비어_있다() {
        GuardianHomeResponse home = service.home(MOM_PRINCIPAL, sea);

        assertThat(home.child().id()).isEqualTo(String.valueOf(sea));
        assertThat(home.child().institutions()).extracting(institution -> institution.institution().id())
                .containsExactly(String.valueOf(centerId));
        assertThat(home.activity()).isEmpty();
    }

    /** 활동의 모양·순서·개수는 GuardianJournalQueryServiceJpaTest 가 본다. 여기서는 홈에 실리는지만 본다. */
    @Test
    void 홈의_활동은_승인된_일지다() {
        SummaryResult summary = em.persistAndFlush(
                SummaryResult.of(sea, LocalDate.of(2026, 8, 21), centerId, 1, "센터 일지", "[]", "[]", "[]", "{}"));
        Long journalId = em.persistAndFlush(ChildContext.of(sea, summary.getId(), "센터 일지")).getId();
        em.clear();

        assertThat(service.home(MOM_PRINCIPAL, sea).activity())
                .extracting(ParentActivityResponse::text, ParentActivityResponse::journalId)
                .containsExactly(tuple("햇살센터에서 8월 21일 일지가 도착했어요", String.valueOf(journalId)));
    }

    @Test
    void 연결되지_않은_아이의_홈은_403() {
        assertThatThrownBy(() -> service.home(DAD_PRINCIPAL, sea))
                .isInstanceOf(ChildException.class)
                .extracting(e -> ((ChildException) e).getErrorCode())
                .isEqualTo(ChildErrorCode.CHILD_NOT_LINKED);
    }

    @Test
    void 끊긴_연결의_아이_홈은_403() {
        guardianOf(sea, MOM).delete();
        em.flush();
        em.clear();

        assertThatThrownBy(() -> service.home(MOM_PRINCIPAL, sea))
                .isInstanceOf(ChildException.class)
                .extracting(e -> ((ChildException) e).getErrorCode())
                .isEqualTo(ChildErrorCode.CHILD_NOT_LINKED);
    }

    /** 연결은 살아 있어도 아이가 삭제됐으면 같은 403 이다. 이유를 나누지 않는다. */
    @Test
    void 삭제된_아이의_홈은_403() {
        em.find(Child.class, sea).delete();
        em.flush();
        em.clear();

        assertThatThrownBy(() -> service.home(MOM_PRINCIPAL, sea))
                .isInstanceOf(ChildException.class)
                .extracting(e -> ((ChildException) e).getErrorCode())
                .isEqualTo(ChildErrorCode.CHILD_NOT_LINKED);
    }

    // --- 대기 중 기관 권한 요청 (G-40) ---

    @Test
    void 아무도_동의하지_않은_기관이_대기_목록에_나온다() {
        assertThat(pendingOf(MOM_PRINCIPAL, sky)).containsExactly(String.valueOf(centerId), String.valueOf(schoolId));
    }

    /** 대기 목록도 아이 기준이다. 아빠가 학교에 동의했으면 엄마에게도 학교 요청은 없다. */
    @Test
    void 다른_보호자가_동의한_기관은_대기_목록에서_빠진다() {
        grant(sky, DAD, schoolId);
        em.clear();

        assertThat(pendingOf(MOM_PRINCIPAL, sky)).containsExactly(String.valueOf(centerId));
    }

    /** 동의했다가 끈 기관은 보호자가 직접 끈 것이라 다시 알리지 않는다. 설정에서 켤 수 있다. */
    @Test
    void 동의했다가_꺼진_기관은_대기_목록에서_빠진다() {
        revoke(grant(sky, MOM, centerId));
        em.clear();

        assertThat(pendingOf(MOM_PRINCIPAL, sky)).containsExactly(String.valueOf(schoolId));
    }

    @Test
    void 명부에서_빠진_기관은_대기_목록에서_빠진다() {
        em.getEntityManager()
                .createQuery("select co from ChildOrganization co "
                        + "where co.childId = :childId and co.organizationId = :organizationId", ChildOrganization.class)
                .setParameter("childId", sky)
                .setParameter("organizationId", schoolId)
                .getSingleResult()
                .delete();
        em.flush();
        em.clear();

        assertThat(pendingOf(MOM_PRINCIPAL, sky)).containsExactly(String.valueOf(centerId));
    }

    @Test
    void 연결되지_않은_아이의_대기_목록은_403() {
        assertThatThrownBy(() -> service.pendingRequests(DAD_PRINCIPAL, sea))
                .isInstanceOf(ChildException.class)
                .extracting(e -> ((ChildException) e).getErrorCode())
                .isEqualTo(ChildErrorCode.CHILD_NOT_LINKED);
    }

    @Test
    void 삭제된_아이의_대기_목록은_403() {
        em.find(Child.class, sea).delete();
        em.flush();
        em.clear();

        assertThatThrownBy(() -> service.pendingRequests(MOM_PRINCIPAL, sea))
                .isInstanceOf(ChildException.class)
                .extracting(e -> ((ChildException) e).getErrorCode())
                .isEqualTo(ChildErrorCode.CHILD_NOT_LINKED);
    }

    private List<String> pendingOf(String principal, Long childId) {
        return service.pendingRequests(principal, childId).stream()
                .map(request -> request.institution().id())
                .toList();
    }

    private SharingConsent grant(Long childId, Long guardianId, Long organizationId) {
        return em.persistAndFlush(
                SharingConsent.grant(childId, guardianId, organizationId, List.of(ConsentField.DAILY_SUMMARY)));
    }

    private void revoke(SharingConsent consent) {
        consent.revoke(consent.getGuardianId(), ConsentRevokeReason.GUARDIAN_REVOKED, NOW);
        em.flush();
    }

    private ChildGuardian guardianOf(Long childId, Long userId) {
        return em.getEntityManager()
                .createQuery("select g from ChildGuardian g where g.childId = :childId and g.userId = :userId",
                        ChildGuardian.class)
                .setParameter("childId", childId)
                .setParameter("userId", userId)
                .getSingleResult();
    }

    private static GuardianChildResponse childOf(List<GuardianChildResponse> children, Long childId) {
        return children.stream()
                .filter(child -> child.id().equals(String.valueOf(childId)))
                .findFirst()
                .orElseThrow();
    }

    private static ConsentState consentOf(GuardianChildResponse child, Long organizationId) {
        return child.institutions().stream()
                .filter(institution -> institution.institution().id().equals(String.valueOf(organizationId)))
                .map(ChildInstitution::consent)
                .findFirst()
                .orElseThrow();
    }
}
