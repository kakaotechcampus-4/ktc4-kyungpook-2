package com.itda.backend.service.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.itda.backend.domain.ChildOrganization;
import com.itda.backend.domain.JournalEntry;
import com.itda.backend.domain.RawRecord;
import com.itda.backend.domain.RawRecordStatus;
import com.itda.backend.exception.SummaryChildNotFoundException;
import com.itda.backend.exception.SummaryRunValidationException;
import com.itda.backend.repository.ChildOrganizationRepository;
import com.itda.backend.repository.JournalEntryRepository;
import com.itda.backend.service.UserService;

/** "지금 요약" — 교사가 자기 기관 아이의 그 날짜 묶음을 마감 전에 요약하도록 요청한다. */
@DataJpaTest
@ActiveProfiles("test")
class SummaryRunServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 8);
    private static final Long ORG = 3L;
    private static final Long CHILD = 8L;

    @Autowired
    private TestEntityManager em;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private ChildOrganizationRepository childOrganizationRepository;

    @MockitoBean
    private UserService userService;

    private SummaryRunRequests runRequests;
    private SummaryRunService summaryRunService;
    private Long rawRecordId;

    @BeforeEach
    void setUp() {
        runRequests = new SummaryRunRequests();
        summaryRunService = new SummaryRunService(userService, childOrganizationRepository, journalEntryRepository,
                runRequests);
        given(userService.getOrganizationIdOf("teacher")).willReturn(ORG);
        em.persist(ChildOrganization.of(CHILD, ORG));
        rawRecordId = em.persist(new RawRecord(String.valueOf(ORG), "school.pdf", "kept/school.pdf",
                "application/pdf", 12, RawRecordStatus.PENDING)).getId();
    }

    private void validated(Long childId, LocalDate date) {
        JournalEntry entry = JournalEntry.of(rawRecordId, date, "블록 놀이를 했다.", 1);
        entry.startMatching();
        entry.confirmMatch(childId);
        entry.startValidating();
        entry.passValidation();
        em.persistAndFlush(entry);
    }

    @Test
    void 요약할_일지가_있으면_그_묶음을_요청해_둔다() {
        validated(CHILD, DATE);

        summaryRunService.requestRun("teacher", CHILD, DATE);

        assertThat(runRequests.contains(new SummaryGroup(CHILD, DATE, ORG))).isTrue();
    }

    @Test
    void 우리_기관_아이가_아니면_없는_것으로_응답한다() {
        assertThatThrownBy(() -> summaryRunService.requestRun("teacher", 99L, DATE))
                .isInstanceOf(SummaryChildNotFoundException.class);
    }

    @Test
    void 요약할_새_일지가_없으면_거절한다() {
        validated(CHILD, DATE.minusDays(1));

        assertThatThrownBy(() -> summaryRunService.requestRun("teacher", CHILD, DATE))
                .isInstanceOf(SummaryRunValidationException.class);
        assertThat(runRequests.contains(new SummaryGroup(CHILD, DATE, ORG))).isFalse();
    }
}
