package com.itda.backend.service.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
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
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.matching.MatchingResult;
import com.itda.backend.domain.matching.MatchingStatus;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.record.RawRecordStatus;
import com.itda.backend.service.user.UserService;
import com.itda.backend.service.agent.AgentQueueGuard;

@DataJpaTest
@ActiveProfiles("test")
@Import(MatchingResultService.class)
class MatchingResultServiceJpaTest {

    @MockitoBean
    private AgentQueueGuard agentQueueGuard;

    private static final String USER_ID = "u-1";

    @Autowired
    private MatchingResultService matchingResultService;

    @Autowired
    private TestEntityManager em;

    @MockitoBean
    private UserService userService;

    private Organization ours;
    private Child child;
    private JournalEntry entry;
    private MatchingResult matchingResult;

    @BeforeEach
    void setUp() {
        ours = em.persist(Organization.of("햇살센터", OrganizationType.CENTER, "1234567890"));
        child = em.persist(Child.of("임유진", LocalDate.of(2019, 11, 26)));
        em.persist(ChildOrganization.of(child.getId(), ours.getId()));
        RawRecord rawRecord = em.persist(new RawRecord(
                String.valueOf(ours.getId()), "관찰일지.txt", "path", "text/plain", 10L, RawRecordStatus.PENDING));
        JournalEntry pending = JournalEntry.of(rawRecord.getId(), LocalDate.of(2026, 9, 1), "블록을 높이 쌓음", 1);
        pending.startMatching();
        pending.requestMatchReview();
        entry = em.persist(pending);
        matchingResult = em.persist(new MatchingResult(entry.getId(), null, new BigDecimal("0.4"),
                MatchingStatus.REVIEW, null, null, null, null, null, null, null));
        child.activate();
        em.persist(SharingConsent.grant(child.getId(), 100L, ours.getId(), List.of(ConsentField.DAILY_SUMMARY)));
        em.flush();
        em.clear();
        given(userService.getOrganizationIdOf(USER_ID)).willReturn(ours.getId());
    }

    @Test
    void 아이를_확정하면_결과와_일지가_함께_저장된다() {
        matchingResultService.resolve(matchingResult.getId(), "assign", child.getId(), "teacher-1", USER_ID);
        em.flush();
        em.clear();

        JournalEntry saved = em.find(JournalEntry.class, entry.getId());
        assertThat(saved.getStatus()).isEqualTo(JournalEntryStatus.MATCHED);
        assertThat(saved.getChildId()).isEqualTo(child.getId());
        assertThat(em.find(MatchingResult.class, matchingResult.getId()).getStatus()).isEqualTo(MatchingStatus.AUTO);
    }

    @Test
    void 제외하면_일지가_제외_상태로_저장된다() {
        matchingResultService.resolve(matchingResult.getId(), "not_ours", null, "teacher-1", USER_ID);
        em.flush();
        em.clear();

        JournalEntry saved = em.find(JournalEntry.class, entry.getId());
        assertThat(saved.getStatus()).isEqualTo(JournalEntryStatus.EXCLUDED);
        assertThat(saved.getChildId()).isNull();
        assertThat(matchingResultService.getQueue(USER_ID)).isEmpty();
    }

    @Test
    void 후보에는_우리_기관_명부의_아이만_나온다() {
        Organization other = em.persist(Organization.of("○○초등학교", OrganizationType.SCHOOL, "1234567891"));
        Child theirs = em.persist(Child.of("박다른", LocalDate.of(2018, 5, 5)));
        em.persist(ChildOrganization.of(theirs.getId(), other.getId()));
        Child left = em.persist(Child.of("최떠남", LocalDate.of(2019, 1, 1)));
        ChildOrganization leftLink = em.persist(ChildOrganization.of(left.getId(), ours.getId()));
        leftLink.delete();
        MatchingResult multi = em.persist(new MatchingResult(entry.getId(), null, new BigDecimal("0.5"),
                MatchingStatus.MULTI, null, null,
                "[{\"child_id\":%d,\"confidence\":0.5},{\"child_id\":%d,\"confidence\":0.4},{\"child_id\":%d,\"confidence\":0.3}]"
                        .formatted(child.getId(), theirs.getId(), left.getId()),
                null, null, null, null));
        em.flush();
        em.clear();

        var item = matchingResultService.getItem(multi.getId(), USER_ID);

        assertThat(item.candidates()).singleElement().satisfies(c -> {
            assertThat(c.childId()).isEqualTo(String.valueOf(child.getId()));
            assertThat(c.name()).isEqualTo("임유진");
        });
    }

    @Test
    void 후보가_모두_명부_밖이면_빈_목록이다() {
        Organization other = em.persist(Organization.of("○○초등학교", OrganizationType.SCHOOL, "1234567891"));
        Child theirs = em.persist(Child.of("박다른", LocalDate.of(2018, 5, 5)));
        em.persist(ChildOrganization.of(theirs.getId(), other.getId()));
        MatchingResult multi = em.persist(new MatchingResult(entry.getId(), null, new BigDecimal("0.5"),
                MatchingStatus.MULTI, null, null,
                "[{\"child_id\":%d,\"confidence\":0.5},{\"child_id\":999999,\"confidence\":0.4}]"
                        .formatted(theirs.getId()),
                null, null, null, null));
        em.flush();
        em.clear();

        assertThat(matchingResultService.getItem(multi.getId(), USER_ID).candidates()).isEmpty();
    }
}
