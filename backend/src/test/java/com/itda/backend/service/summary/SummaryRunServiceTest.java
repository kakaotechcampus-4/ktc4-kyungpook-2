package com.itda.backend.service.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

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
import com.itda.backend.service.agent.WorkerProperties;

/** "지금 요약" — 교사가 자기 기관 아이의 그 날짜 묶음을 마감 전에 요약하도록 요청한다. */
@DataJpaTest
@ActiveProfiles("test")
class SummaryRunServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 8);
    private static final Long ORG = 3L;
    private static final Long CHILD = 8L;
    private static final SummaryGroup GROUP = new SummaryGroup(CHILD, DATE, ORG);
    private static final SummaryProperties PROPERTIES = new SummaryProperties(new WorkerProperties(true, 5000, 10),
            LocalTime.of(3, 0), Duration.ofMinutes(30), KST, Duration.ofSeconds(120));

    @Autowired
    private TestEntityManager em;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private ChildOrganizationRepository childOrganizationRepository;

    @MockitoBean
    private UserService userService;

    private SummaryRunRequests runRequests;
    private Long rawRecordId;

    @BeforeEach
    void setUp() {
        runRequests = new SummaryRunRequests();
        given(userService.getOrganizationIdOf("teacher")).willReturn(ORG);
        em.persist(ChildOrganization.of(CHILD, ORG));
        rawRecordId = em.persist(new RawRecord(String.valueOf(ORG), "school.pdf", "kept/school.pdf",
                "application/pdf", 12, RawRecordStatus.PENDING)).getId();
    }

    private SummaryRunService serviceAt(LocalDateTime now) {
        return new SummaryRunService(userService, childOrganizationRepository, journalEntryRepository,
                runRequests, PROPERTIES, Clock.fixed(now.atZone(KST).toInstant(), KST));
    }

    private static Instant kst(LocalDateTime at) {
        return at.atZone(KST).toInstant();
    }

    private void validated(Long childId, LocalDate date) {
        JournalEntry entry = JournalEntry.of(rawRecordId, date, "블록 놀이를 했다.", 1);
        entry.startMatching();
        entry.confirmMatch(childId);
        entry.startValidating();
        entry.passValidation();
        em.persistAndFlush(entry);
    }

    private void notMatchedYet(LocalDate date) {
        em.persistAndFlush(JournalEntry.of(rawRecordId, date, "아직 매칭 전인 기록", 1));
    }

    @Test
    void 요약할_일지가_있으면_그_묶음을_요청해_둔다() {
        validated(CHILD, DATE);

        serviceAt(DATE.atTime(17, 0)).requestRun("teacher", CHILD, DATE);

        assertThat(runRequests.isRequested(GROUP, kst(DATE.atTime(17, 1)))).isTrue();
    }

    @Test
    void 업로드_직후라_매칭_검증_중이어도_받아_두고_끝나면_요약한다() {
        notMatchedYet(DATE);

        serviceAt(DATE.atTime(17, 1)).requestRun("teacher", CHILD, DATE);

        assertThat(runRequests.isRequested(GROUP, kst(DATE.atTime(17, 2)))).isTrue();
    }

    @Test
    void 요청은_그_날짜의_마감까지만_유효하다() {
        // 마감이 지나면 어차피 자동으로 요약된다. 요청이 계속 남으면 한참 뒤 올라온 일지가 디바운스 없이 요약된다.
        validated(CHILD, DATE);

        serviceAt(DATE.atTime(17, 0)).requestRun("teacher", CHILD, DATE);

        assertThat(runRequests.isRequested(GROUP, kst(LocalDateTime.of(2026, 10, 9, 2, 59)))).isTrue();
        assertThat(runRequests.isRequested(GROUP, kst(LocalDateTime.of(2026, 10, 9, 3, 0)))).isFalse();
    }

    @Test
    void 마감이_지난_날짜는_디바운스만큼만_유효하다() {
        validated(CHILD, DATE);
        LocalDateTime now = LocalDateTime.of(2026, 10, 10, 14, 0);

        serviceAt(now).requestRun("teacher", CHILD, DATE);

        assertThat(runRequests.isRequested(GROUP, kst(now.plusMinutes(29)))).isTrue();
        assertThat(runRequests.isRequested(GROUP, kst(now.plusMinutes(30)))).isFalse();
    }

    @Test
    void 우리_기관_아이가_아니면_없는_것으로_응답한다() {
        assertThatThrownBy(() -> serviceAt(DATE.atTime(17, 0)).requestRun("teacher", 99L, DATE))
                .isInstanceOf(SummaryChildNotFoundException.class);
    }

    @Test
    void 요약할_일지도_처리_중인_일지도_없으면_거절한다() {
        validated(CHILD, DATE.minusDays(1));

        assertThatThrownBy(() -> serviceAt(DATE.atTime(17, 0)).requestRun("teacher", CHILD, DATE))
                .isInstanceOf(SummaryRunValidationException.class);
        assertThat(runRequests.isRequested(GROUP, kst(DATE.atTime(17, 0)))).isFalse();
    }
}
