package com.itda.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.itda.backend.domain.Child;
import com.itda.backend.domain.ChildOrganization;
import com.itda.backend.domain.JournalEntry;
import com.itda.backend.domain.JournalEntryStatus;
import com.itda.backend.domain.MatchingResult;
import com.itda.backend.domain.MatchingStatus;
import com.itda.backend.domain.Organization;
import com.itda.backend.domain.OrganizationType;
import com.itda.backend.domain.RawRecord;
import com.itda.backend.domain.RawRecordStatus;

/** resolve 가 matching_result 와 journal_entry 를 한 트랜잭션에서 실제로 저장하는지 DB 로 확인한다. */
@DataJpaTest
@ActiveProfiles("test")
@Import(MatchingResultService.class)
class MatchingResultServiceJpaTest {

    private static final String USER_ID = "u-1";

    @Autowired
    private MatchingResultService matchingResultService;

    @Autowired
    private TestEntityManager em;

    @MockitoBean
    private UserService userService;

    private Child child;
    private JournalEntry entry;
    private MatchingResult matchingResult;

    @BeforeEach
    void setUp() {
        Organization ours = em.persist(Organization.of("햇살센터", OrganizationType.CENTER, "1234567890"));
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
        em.flush();
        em.getEntityManager()
                .createQuery("update Child c set c.status = com.itda.backend.domain.ChildStatus.ACTIVE where c.id = :id")
                .setParameter("id", child.getId())
                .executeUpdate();
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
}
