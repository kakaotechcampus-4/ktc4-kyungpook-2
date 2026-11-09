package com.itda.backend.service.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.ConsentRevokeReason;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.record.RawRecordStatus;
import com.itda.backend.domain.summary.SummaryRunRequest;
import com.itda.backend.exception.summary.SummaryChildNotFoundException;
import com.itda.backend.exception.summary.SummaryErrorCode;
import com.itda.backend.exception.summary.SummaryException;
import com.itda.backend.exception.summary.SummaryRunValidationException;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.consent.SharingConsentRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.summary.SummaryRunRequestRepository;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.user.UserService;

@DataJpaTest
@ActiveProfiles("test")
class SummaryRunServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 8);
    private static final Long ORG = 3L;
    private static final Long CHILD = 8L;
    private static final Long OTHER_ORG = 4L;
    private static final Long GUARDIAN = 100L;
    private static final SummaryGroup GROUP = new SummaryGroup(CHILD, DATE, ORG);

    @Autowired
    private TestEntityManager em;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private ChildOrganizationRepository childOrganizationRepository;

    @Autowired
    private SummaryRunRequestRepository summaryRunRequestRepository;

    @Autowired
    private SharingConsentRepository sharingConsentRepository;

    @MockitoBean
    private UserService userService;

    private SummaryRunRequests runRequests;
    private Long rawRecordId;

    @BeforeEach
    void setUp() {
        runRequests = new SummaryRunRequests(summaryRunRequestRepository);
        given(userService.getOrganizationIdOf("teacher")).willReturn(ORG);
        em.persist(ChildOrganization.of(CHILD, ORG));
        grant(CHILD, ORG);
        rawRecordId = em.persist(new RawRecord(String.valueOf(ORG), "school.pdf", "kept/school.pdf",
                "application/pdf", 12, RawRecordStatus.PENDING)).getId();
    }

    private SummaryRunService serviceAt(LocalDateTime now) {
        return new SummaryRunService(userService, childOrganizationRepository, journalEntryRepository,
                runRequests, new ConsentStateReader(sharingConsentRepository),
                Clock.fixed(now.atZone(KST).toInstant(), KST));
    }

    private SharingConsent grant(Long childId, Long organizationId) {
        return em.persistAndFlush(
                SharingConsent.grant(childId, GUARDIAN, organizationId, List.of(ConsentField.DAILY_SUMMARY)));
    }

    private Long matchedThenRevoked() {
        Long childId = 9L;
        em.persist(ChildOrganization.of(childId, ORG));
        grant(childId, ORG).revoke(GUARDIAN, ConsentRevokeReason.GUARDIAN_REVOKED, DATE.atTime(9, 0));
        em.flush();
        validated(childId, DATE);
        return childId;
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

        assertThat(summaryRunRequestRepository.findAll()).singleElement()
                .extracting(SummaryRunRequest::getRequestedAt).isEqualTo(DATE.atTime(17, 0));
    }

    @Test
    void 업로드_직후라_매칭_검증_중이어도_받아_두고_끝나면_요약한다() {
        notMatchedYet(DATE);

        serviceAt(DATE.atTime(17, 1)).requestRun("teacher", CHILD, DATE);

        assertThat(runRequests.isRequested(GROUP)).isTrue();
    }

    @Test
    void 다시_누르면_요청은_하나이고_누른_시각만_바뀐다() {
        validated(CHILD, DATE);

        serviceAt(DATE.atTime(17, 0)).requestRun("teacher", CHILD, DATE);
        serviceAt(DATE.atTime(17, 5)).requestRun("teacher", CHILD, DATE);

        em.flush();
        em.clear();
        assertThat(summaryRunRequestRepository.findAll()).singleElement()
                .extracting(SummaryRunRequest::getRequestedAt).isEqualTo(DATE.atTime(17, 5));
    }

    @Test
    void 우리_기관_아이가_아니면_없는_것으로_응답한다() {
        assertThatThrownBy(() -> serviceAt(DATE.atTime(17, 0)).requestRun("teacher", 99L, DATE))
                .isInstanceOf(SummaryChildNotFoundException.class);
    }

    @Test
    void 우리_기관_동의를_철회한_아이는_없는_것으로_응답한다() {
        Long childId = matchedThenRevoked();

        assertThatThrownBy(() -> serviceAt(DATE.atTime(17, 0)).requestRun("teacher", childId, DATE))
                .isInstanceOf(SummaryChildNotFoundException.class);
        assertThat(summaryRunRequestRepository.findAll()).isEmpty();
    }

    @Test
    void 다른_기관에만_동의한_아이도_없는_것으로_응답한다() {
        Long childId = matchedThenRevoked();
        grant(childId, OTHER_ORG);

        assertThatThrownBy(() -> serviceAt(DATE.atTime(17, 0)).requestRun("teacher", childId, DATE))
                .isInstanceOf(SummaryChildNotFoundException.class);
    }

    @Test
    void 다시_동의한_아이는_요청을_받는다() {
        Long childId = matchedThenRevoked();
        grant(childId, ORG);

        serviceAt(DATE.atTime(17, 0)).requestRun("teacher", childId, DATE);

        assertThat(runRequests.isRequested(new SummaryGroup(childId, DATE, ORG))).isTrue();
    }

    @Test
    void 요약할_일지도_처리_중인_일지도_없으면_거절한다() {
        validated(CHILD, DATE.minusDays(1));

        assertThatThrownBy(() -> serviceAt(DATE.atTime(17, 0)).requestRun("teacher", CHILD, DATE))
                .isInstanceOf(SummaryRunValidationException.class);
        assertThat(runRequests.isRequested(GROUP)).isFalse();
    }

    @Test
    void 요약_중인_묶음은_끝날_때까지_다시_요청받지_않는다() {
        validated(CHILD, DATE);
        JournalEntry summarizing = JournalEntry.of(rawRecordId, DATE, "요약 중인 기록", 2);
        summarizing.startMatching();
        summarizing.confirmMatch(CHILD);
        summarizing.startValidating();
        summarizing.passValidation();
        summarizing.startSummarizing();
        em.persistAndFlush(summarizing);

        assertThatThrownBy(() -> serviceAt(DATE.atTime(17, 0)).requestRun("teacher", CHILD, DATE))
                .isInstanceOf(SummaryException.class)
                .extracting("errorCode").isEqualTo(SummaryErrorCode.IN_PROGRESS);
        assertThat(runRequests.isRequested(GROUP)).isFalse();
    }
}
