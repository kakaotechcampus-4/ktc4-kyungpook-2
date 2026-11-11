package com.itda.backend.service.checklist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
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

import com.itda.backend.domain.checklist.Checklist;
import com.itda.backend.domain.checklist.ChecklistTarget;
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.user.User;
import com.itda.backend.dto.response.checklist.SentChecklistPageResponse;
import com.itda.backend.dto.response.checklist.SentChecklistResponse;
import com.itda.backend.exception.checklist.ChecklistErrorCode;
import com.itda.backend.exception.checklist.ChecklistException;
import com.itda.backend.fixture.MutableClock;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.service.user.UserService;

@DataJpaTest
@ActiveProfiles("test")
@Import({ChecklistService.class, ChecklistServiceJpaTest.ClockConfig.class})
class ChecklistServiceJpaTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 9, 0);
    private static final String STAFF = "u-staff";
    private static final List<String> ITEMS = List.of("실내화를 새로 준비해주세요.", "현장학습 동의서를 확인해주세요.");

    @TestConfiguration
    static class ClockConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired private ChecklistService checklistService;
    @Autowired private TestEntityManager em;
    @Autowired private MutableClock clock;
    @MockitoBean private UserService userService;

    private Organization ours;
    private Child sky;
    private Child sea;
    private Child star;
    private User mom;

    @BeforeEach
    void setUp() {
        ours = em.persist(OrganizationFixture.center());
        Organization other = em.persist(OrganizationFixture.center());
        sky = em.persist(Child.of("하늘", LocalDate.of(2017, 3, 14)));
        sea = em.persist(Child.of("바다", LocalDate.of(2018, 5, 2)));
        star = em.persist(Child.of("별", LocalDate.of(2016, 1, 9)));
        em.persist(ChildOrganization.of(sky.getId(), ours.getId()));
        em.persist(ChildOrganization.of(sea.getId(), ours.getId()));
        em.persist(ChildOrganization.of(star.getId(), other.getId()));
        User staff = em.persist(UserFixture.organizationUser("cl-staff", ours.getId()));
        mom = em.persist(UserFixture.parent("cl-mom"));
        em.flush();
        em.clear();
        given(userService.getOrganizationIdOf(STAFF)).willReturn(ours.getId());
        given(userService.getUserIdOf(STAFF)).willReturn(staff.getId());
    }

    private SentChecklistResponse send(Child... children) {
        clock.setTo(clock.instant().atZone(MutableClock.KST).toLocalDateTime().plusMinutes(1));
        return checklistService.send(STAFF, ITEMS, Arrays.stream(children).map(Child::getId).toList());
    }

    private void flushClear() {
        em.flush();
        em.clear();
    }

    @Test
    void 보내면_요청_한_줄과_받은_아이마다_한_줄이_생긴다() {
        SentChecklistResponse sent = send(sky, sea);
        flushClear();

        assertThat(sent.total()).isEqualTo(2);
        assertThat(sent.confirmed()).isZero();
        assertThat(sent.targets()).extracting(t -> t.child().name()).containsExactly("하늘", "바다");
        Checklist stored = em.find(Checklist.class, Long.valueOf(sent.id()));
        assertThat(stored.getItems()).containsExactlyElementsOf(ITEMS);
        assertThat(stored.getOrganizationId()).isEqualTo(ours.getId());
        assertThat(em.getEntityManager().createQuery("select t from ChecklistTarget t", ChecklistTarget.class)
                .getResultList()).hasSize(2);
    }

    @Test
    void 명부에_없는_아이가_섞이면_아무것도_남기지_않고_400이다() {
        assertThatThrownBy(() -> send(sky, star))
                .isInstanceOfSatisfying(ChecklistException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ChecklistErrorCode.INVALID_CHECKLIST_TARGET));
        assertThatThrownBy(() -> checklistService.send(STAFF, ITEMS, List.of(sky.getId(), 999_999L)))
                .isInstanceOf(ChecklistException.class);
        flushClear();
        assertThat(em.getEntityManager().createQuery("select count(c) from Checklist c", Long.class)
                .getSingleResult()).isZero();
    }

    @Test
    void 삭제된_아이나_명부에서_빠진_아이에게는_보낼_수_없다() {
        Child deleted = em.find(Child.class, sky.getId());
        deleted.delete();
        flushClear();
        assertThatThrownBy(() -> send(sky)).isInstanceOf(ChecklistException.class);

        em.getEntityManager().createQuery("select co from ChildOrganization co where co.childId = :id", ChildOrganization.class)
                .setParameter("id", sea.getId()).getSingleResult().delete();
        flushClear();
        assertThatThrownBy(() -> send(sea)).isInstanceOf(ChecklistException.class);
    }

    @Test
    void 목록은_최신순이고_명부에서_빠지거나_삭제된_아이는_숫자에서_빠진다() {
        SentChecklistResponse first = send(sky, sea);
        SentChecklistResponse second = send(sea);
        flushClear();
        em.getEntityManager().createQuery("select co from ChildOrganization co where co.childId = :id", ChildOrganization.class)
                .setParameter("id", sky.getId()).getSingleResult().delete();
        em.getEntityManager()
                .createQuery("update ChecklistTarget t set t.confirmedAt = :at, t.confirmedBy = :by "
                        + "where t.childId = :child and t.checklistId = :cl")
                .setParameter("at", NOW).setParameter("by", mom.getId())
                .setParameter("child", sea.getId()).setParameter("cl", Long.valueOf(first.id()))
                .executeUpdate();
        flushClear();

        SentChecklistPageResponse page = checklistService.list(STAFF, null);

        assertThat(page.checklists()).extracting(SentChecklistResponse::id).containsExactly(second.id(), first.id());
        assertThat(page.nextBefore()).isNull();
        SentChecklistResponse older = page.checklists().get(1);
        assertThat(older.total()).isEqualTo(1);
        assertThat(older.confirmed()).isEqualTo(1);
        assertThat(older.targets()).extracting(t -> t.child().name(), SentChecklistResponse.Target::onRoster)
                .containsExactly(tuple("하늘", false),
                        tuple("바다", true));
        assertThat(older.targets().get(1).confirmedAt()).isNotNull();

        Child deleted = em.find(Child.class, sea.getId());
        deleted.delete();
        flushClear();
        assertThat(checklistService.list(STAFF, null).checklists().get(0).total()).isZero();
    }

    @Test
    void 쉰_개씩_보여주고_before_로_이어_보며_기준을_내려도_다음_쪽이_열린다() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < ChecklistService.PAGE_SIZE + 2; i++) {
            ids.add(send(sky).id());
        }
        flushClear();

        SentChecklistPageResponse first = checklistService.list(STAFF, null);
        assertThat(first.checklists()).hasSize(ChecklistService.PAGE_SIZE);
        assertThat(first.nextBefore()).isEqualTo(ids.get(2));

        checklistService.cancel(STAFF, Long.valueOf(first.nextBefore()));
        flushClear();
        SentChecklistPageResponse second = checklistService.list(STAFF, Long.valueOf(first.nextBefore()));
        assertThat(second.checklists()).extracting(SentChecklistResponse::id).containsExactly(ids.get(1), ids.get(0));
        assertThat(second.nextBefore()).isNull();
    }

    @Test
    void 다른_기관_요청을_before_로_주면_400이다() {
        Organization other = em.persist(OrganizationFixture.center());
        Checklist foreign = em.persist(Checklist.send(other.getId(), ITEMS, mom.getId(), NOW));
        flushClear();

        assertThatThrownBy(() -> checklistService.list(STAFF, foreign.getId()))
                .isInstanceOfSatisfying(ChecklistException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ChecklistErrorCode.INVALID_CHECKLIST_CURSOR));
    }

    @Test
    void 내리면_목록에서_빠지고_다시_내려도_처음_시각이_남는다() {
        SentChecklistResponse sent = send(sky);
        flushClear();
        clock.setTo(NOW.plusHours(1));
        checklistService.cancel(STAFF, Long.valueOf(sent.id()));
        flushClear();
        clock.setTo(NOW.plusHours(2));
        checklistService.cancel(STAFF, Long.valueOf(sent.id()));
        flushClear();

        assertThat(em.find(Checklist.class, Long.valueOf(sent.id())).getCanceledAt()).isEqualTo(NOW.plusHours(1));
        assertThat(checklistService.list(STAFF, null).checklists()).isEmpty();
    }

    @Test
    void 다른_기관_요청이나_없는_요청은_내릴_수_없다() {
        Organization other = em.persist(OrganizationFixture.center());
        Checklist foreign = em.persist(Checklist.send(other.getId(), ITEMS, mom.getId(), NOW));
        flushClear();

        for (Long id : new Long[]{foreign.getId(), 999_999L}) {
            assertThatThrownBy(() -> checklistService.cancel(STAFF, id))
                    .isInstanceOfSatisfying(ChecklistException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ChecklistErrorCode.CHECKLIST_NOT_FOUND));
        }
        assertThat(em.find(Checklist.class, foreign.getId()).isCanceled()).isFalse();
    }
}
