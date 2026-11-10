package com.itda.backend.service.checklist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.user.User;
import com.itda.backend.dto.response.checklist.GuardianChecklistResponse;
import com.itda.backend.exception.checklist.ChecklistErrorCode;
import com.itda.backend.exception.checklist.ChecklistException;
import com.itda.backend.exception.child.ChildErrorCode;
import com.itda.backend.exception.child.ChildException;
import com.itda.backend.fixture.MutableClock;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.service.user.UserService;
import com.itda.backend.service.child.GuardianChildGuard;

@DataJpaTest
@ActiveProfiles("test")
@Import({GuardianChecklistService.class, GuardianChildGuard.class, ChecklistService.class, GuardianChecklistServiceJpaTest.ClockConfig.class})
class GuardianChecklistServiceJpaTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 9, 0);
    private static final String STAFF = "u-staff";
    private static final String MOM = "u-mom";
    private static final String DAD = "u-dad";
    private static final String STRANGER = "u-stranger";

    @TestConfiguration
    static class ClockConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired private GuardianChecklistService guardianChecklistService;
    @Autowired private ChecklistService checklistService;
    @Autowired private TestEntityManager em;
    @Autowired private MutableClock clock;
    @MockitoBean private UserService userService;

    private Organization ours;
    private Child sky;
    private User mom;
    private User dad;
    private int minutes;

    @BeforeEach
    void setUp() {
        ours = em.persist(OrganizationFixture.center());
        sky = em.persist(Child.of("하늘", LocalDate.of(2017, 3, 14)));
        em.persist(ChildOrganization.of(sky.getId(), ours.getId()));
        User staff = em.persist(UserFixture.organizationUser("gcl-staff", ours.getId()));
        mom = em.persist(UserFixture.parent("gcl-mom"));
        dad = em.persist(UserFixture.parent("gcl-dad"));
        User stranger = em.persist(UserFixture.parent("gcl-stranger"));
        em.persist(ChildGuardian.link(sky.getId(), mom.getId(), 1L));
        em.persist(ChildGuardian.link(sky.getId(), dad.getId(), 1L));
        em.flush();
        em.clear();
        given(userService.getOrganizationIdOf(STAFF)).willReturn(ours.getId());
        given(userService.getUserIdOf(STAFF)).willReturn(staff.getId());
        given(userService.getParentIdOf(MOM)).willReturn(mom.getId());
        given(userService.getParentIdOf(DAD)).willReturn(dad.getId());
        given(userService.getParentIdOf(STRANGER)).willReturn(stranger.getId());
    }

    private Long send(String item) {
        clock.setTo(NOW.plusMinutes(++minutes));
        return Long.valueOf(checklistService.send(STAFF, List.of(item), List.of(sky.getId())).id());
    }

    private void flushClear() {
        em.flush();
        em.clear();
    }

    @Test
    void 연결되지_않은_보호자는_목록도_확인도_403이다() {
        Long id = send("실내화");
        flushClear();

        assertThatThrownBy(() -> guardianChecklistService.list(STRANGER, sky.getId(), false))
                .isInstanceOfSatisfying(ChildException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ChildErrorCode.CHILD_NOT_LINKED));
        assertThatThrownBy(() -> guardianChecklistService.confirm(STRANGER, sky.getId(), id))
                .isInstanceOf(ChildException.class);
    }

    @Test
    void 삭제된_아이는_목록도_확인도_403이다() {
        Long id = send("실내화");
        flushClear();
        em.find(Child.class, sky.getId()).delete();
        flushClear();

        assertThatThrownBy(() -> guardianChecklistService.list(MOM, sky.getId(), false))
                .isInstanceOfSatisfying(ChildException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ChildErrorCode.CHILD_NOT_LINKED));
        assertThatThrownBy(() -> guardianChecklistService.list(MOM, sky.getId(), true))
                .isInstanceOf(ChildException.class);
        assertThatThrownBy(() -> guardianChecklistService.confirm(MOM, sky.getId(), id))
                .isInstanceOf(ChildException.class);
    }

    @Test
    void 확인_안_한_것은_전부_먼저_확인한_것은_최근_스무_개만_보인다() {
        List<Long> confirmed = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            confirmed.add(send("확인할 것 " + i));
        }
        List<Long> open = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            open.add(send("남은 것 " + i));
        }
        flushClear();
        for (Long id : confirmed) {
            guardianChecklistService.confirm(MOM, sky.getId(), id);
        }
        flushClear();

        List<GuardianChecklistResponse> all = guardianChecklistService.list(DAD, sky.getId(), false);

        assertThat(all).hasSize(21 + GuardianChecklistService.CONFIRMED_LIMIT);
        assertThat(all.subList(0, 21)).allSatisfy(item -> assertThat(item.status()).isEqualTo("needs_check"));
        assertThat(all.subList(0, 21)).extracting(GuardianChecklistResponse::id)
                .containsExactlyElementsOf(open.reversed().stream().map(String::valueOf).toList());
        assertThat(all.subList(21, all.size())).extracting(GuardianChecklistResponse::id)
                .containsExactlyElementsOf(confirmed.reversed().subList(0, 20).stream().map(String::valueOf).toList());
        assertThat(all).extracting(GuardianChecklistResponse::id).doesNotHaveDuplicates();

        List<GuardianChecklistResponse> needsCheck = guardianChecklistService.list(DAD, sky.getId(), true);
        assertThat(needsCheck).hasSize(21).allSatisfy(item -> assertThat(item.status()).isEqualTo("needs_check"));
    }

    @Test
    void 내린_요청과_명부에서_빠진_기관의_요청은_보이지_않는다() {
        Long canceled = send("내릴 것");
        Long kept = send("남길 것");
        flushClear();
        checklistService.cancel(STAFF, canceled);
        flushClear();
        assertThat(guardianChecklistService.list(MOM, sky.getId(), false))
                .extracting(GuardianChecklistResponse::id).containsExactly(String.valueOf(kept));

        em.getEntityManager().createQuery("select co from ChildOrganization co where co.childId = :id", ChildOrganization.class)
                .setParameter("id", sky.getId()).getSingleResult().delete();
        flushClear();
        assertThat(guardianChecklistService.list(MOM, sky.getId(), false)).isEmpty();
        assertThat(guardianChecklistService.list(MOM, sky.getId(), true)).isEmpty();
        assertThatThrownBy(() -> guardianChecklistService.confirm(MOM, sky.getId(), kept))
                .isInstanceOfSatisfying(ChecklistException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ChecklistErrorCode.CHECKLIST_NOT_FOUND));
    }

    @Test
    void 보낸_뒤에_연결된_보호자도_본다() {
        Long id = send("실내화");
        User aunt = em.persist(UserFixture.parent("gcl-aunt"));
        em.persist(ChildGuardian.link(sky.getId(), aunt.getId(), 1L));
        flushClear();
        given(userService.getParentIdOf("u-aunt")).willReturn(aunt.getId());

        assertThat(guardianChecklistService.list("u-aunt", sky.getId(), false))
                .extracting(GuardianChecklistResponse::id).containsExactly(String.valueOf(id));
    }

    @Test
    void 첫_확인_응답이_바로_확인됨이고_다른_보호자도_이름을_본다() {
        Long id = send("실내화");
        flushClear();
        assertThat(guardianChecklistService.list(MOM, sky.getId(), false).get(0).status()).isEqualTo("needs_check");
        clock.setTo(NOW.plusHours(1));

        GuardianChecklistResponse confirmed = guardianChecklistService.confirm(MOM, sky.getId(), id);

        assertThat(confirmed.status()).isEqualTo("confirmed");
        assertThat(confirmed.confirmedAt()).isEqualTo(NOW.plusHours(1));
        assertThat(confirmed.institution().id()).isEqualTo(String.valueOf(ours.getId()));
        flushClear();
        GuardianChecklistResponse seenByDad = guardianChecklistService.list(DAD, sky.getId(), false).get(0);
        assertThat(seenByDad.status()).isEqualTo("confirmed");
        assertThat(seenByDad.confirmedAt()).isEqualTo(NOW.plusHours(1));
        assertThat(guardianChecklistService.list(DAD, sky.getId(), true)).isEmpty();
    }

    @Test
    void 이미_확인된_요청을_다시_확인하면_먼저_한_사람이_남고_성공한다() {
        Long id = send("실내화");
        flushClear();
        clock.setTo(NOW.plusHours(1));
        guardianChecklistService.confirm(MOM, sky.getId(), id);
        flushClear();
        clock.setTo(NOW.plusHours(2));

        GuardianChecklistResponse second = guardianChecklistService.confirm(DAD, sky.getId(), id);

        assertThat(second.status()).isEqualTo("confirmed");
        assertThat(second.confirmedAt()).isEqualTo(NOW.plusHours(1));
    }

    @Test
    void 내린_요청이나_이_아이가_받지_않은_요청은_404다() {
        Long canceled = send("내릴 것");
        Child sea = em.persist(Child.of("바다", LocalDate.of(2018, 5, 2)));
        em.persist(ChildOrganization.of(sea.getId(), ours.getId()));
        flushClear();
        Long forSea = Long.valueOf(checklistService.send(STAFF, List.of("바다 것"), List.of(sea.getId())).id());
        checklistService.cancel(STAFF, canceled);
        flushClear();

        for (Long id : new Long[]{canceled, forSea, 999_999L}) {
            assertThatThrownBy(() -> guardianChecklistService.confirm(MOM, sky.getId(), id))
                    .isInstanceOfSatisfying(ChecklistException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ChecklistErrorCode.CHECKLIST_NOT_FOUND));
        }
    }
}
