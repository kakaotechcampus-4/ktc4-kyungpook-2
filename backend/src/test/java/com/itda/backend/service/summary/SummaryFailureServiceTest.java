package com.itda.backend.service.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.ConsentRevokeReason;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.record.RawRecordStatus;
import com.itda.backend.dto.response.summary.FailedSummaryGroupResponse;
import com.itda.backend.exception.summary.SummaryChildNotFoundException;
import com.itda.backend.exception.summary.SummaryErrorCode;
import com.itda.backend.exception.summary.SummaryException;
import com.itda.backend.global.config.ClockConfig;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.user.UserService;

/** 요약 실패 묶음을 검토에 올리고 교사가 다시 요약하거나 요약 없이 마무리한다 (#369). */
@DataJpaTest
@ActiveProfiles("test")
@Import({SummaryFailureService.class, SummaryRunRequests.class, ConsentStateReader.class, ClockConfig.class})
class SummaryFailureServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 8);
    private static final Long ORG = 3L;
    private static final Long OTHER_ORG = 4L;
    private static final String TEACHER = "99";

    @Autowired
    private TestEntityManager em;

    @Autowired
    private SummaryFailureService service;

    @Autowired
    private SummaryRunRequests runRequests;

    @MockitoBean
    private UserService userService;

    /** 기본은 자동 요약 꺼짐(mock 의 false). */
    @MockitoBean
    private SummaryProperties summaryProperties;

    private Long junho;
    private Long seojun;
    private Long school;
    private Long center;

    @BeforeEach
    void setUp() {
        given(userService.getOrganizationIdOf(TEACHER)).willReturn(ORG);
        junho = em.persist(Child.of("김준호", LocalDate.of(2020, 3, 1))).getId();
        seojun = em.persist(Child.of("서준호", LocalDate.of(2020, 5, 1))).getId();
        consent(junho, ORG);
        consent(seojun, ORG);
        school = em.persist(new RawRecord(String.valueOf(ORG), "school.pdf", "kept/school.pdf", "application/pdf", 12,
                RawRecordStatus.PENDING)).getId();
        center = em.persist(new RawRecord(String.valueOf(OTHER_ORG), "center.pdf", "kept/center.pdf",
                "application/pdf", 12, RawRecordStatus.PENDING)).getId();
    }

    private SharingConsent consent(Long childId, Long organizationId) {
        return em.persistAndFlush(SharingConsent.grant(childId, 100L, organizationId,
                List.of(ConsentField.DAILY_SUMMARY)));
    }

    private JournalEntry validated(Long rawRecordId, Long childId, LocalDate date, String content) {
        JournalEntry entry = JournalEntry.of(rawRecordId, date, content, 1);
        entry.startMatching();
        entry.confirmMatch(childId);
        entry.startValidating();
        entry.passValidation();
        return entry;
    }

    private JournalEntry failed(Long rawRecordId, Long childId, LocalDate date, String content) {
        JournalEntry entry = validated(rawRecordId, childId, date, content);
        entry.startSummarizing();
        entry.failSummary();
        return em.persistAndFlush(entry);
    }

    private JournalEntryStatus statusOf(JournalEntry entry) {
        em.flush();
        em.clear();
        return em.find(JournalEntry.class, entry.getId()).getStatus();
    }

    @Test
    void 우리_기관의_요약_실패_일지를_아이_날짜_묶음으로_오래된_날짜부터_보여준다() {
        JournalEntry later = failed(school, junho, DATE.plusDays(1), "다음 날 기록");
        JournalEntry first = failed(school, junho, DATE, "블록 놀이를 했다.");
        JournalEntry second = failed(school, junho, DATE, "점심을 먹었다.");
        JournalEntry other = failed(school, seojun, DATE, "그림을 그렸다.");

        List<FailedSummaryGroupResponse> groups = service.getFailedGroups(TEACHER);

        assertThat(groups).extracting(FailedSummaryGroupResponse::childId, FailedSummaryGroupResponse::entryDate)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(junho, DATE),
                        org.assertj.core.groups.Tuple.tuple(seojun, DATE),
                        org.assertj.core.groups.Tuple.tuple(junho, DATE.plusDays(1)));
        FailedSummaryGroupResponse group = groups.get(0);
        assertThat(group.childName()).isEqualTo("김준호");
        assertThat(group.reason()).isEqualTo("NO_GROUNDED_CLAIMS");
        assertThat(group.entries()).extracting(FailedSummaryGroupResponse.Entry::journalEntryId)
                .containsExactly(first.getId(), second.getId());
        assertThat(group.entries().get(0).content()).isEqualTo("블록 놀이를 했다.");
        assertThat(groups.get(1).entries()).extracting(FailedSummaryGroupResponse.Entry::journalEntryId)
                .containsExactly(other.getId());
        assertThat(groups.get(2).entries()).extracting(FailedSummaryGroupResponse.Entry::journalEntryId)
                .containsExactly(later.getId());
    }

    @Test
    void 다른_기관의_요약_실패와_다른_상태의_일지는_보이지_않는다() {
        consent(junho, OTHER_ORG);
        failed(center, junho, DATE, "센터 기록");
        em.persistAndFlush(validated(school, junho, DATE, "요약 대기"));

        assertThat(service.getFailedGroups(TEACHER)).isEmpty();
    }

    @Test
    void 우리_기관_동의가_없거나_철회된_아이의_요약_실패는_보이지_않는다() {
        Long minji = em.persist(Child.of("박민지", LocalDate.of(2020, 7, 1))).getId();
        failed(school, minji, DATE, "동의 없음");
        em.getEntityManager().createQuery("select c from SharingConsent c where c.childId = :id", SharingConsent.class)
                .setParameter("id", seojun).getSingleResult()
                .revoke(100L, ConsentRevokeReason.GUARDIAN_REVOKED, DATE.atStartOfDay());
        failed(school, seojun, DATE, "철회됨");

        assertThat(service.getFailedGroups(TEACHER)).isEmpty();
    }

    @Test
    void 다시_요약하면_그_묶음의_요약_실패_일지만_요약_대기로_돌아간다() {
        JournalEntry target = failed(school, junho, DATE, "블록 놀이를 했다.");
        JournalEntry otherDay = failed(school, junho, DATE.plusDays(1), "다음 날 기록");

        service.retry(TEACHER, junho, DATE);

        assertThat(statusOf(target)).isEqualTo(JournalEntryStatus.VALIDATED);
        assertThat(statusOf(otherDay)).isEqualTo(JournalEntryStatus.SUMMARY_FAILED);
    }

    @Test
    void 자동_요약이_꺼져_있으면_다시_요약할_때_요약_요청도_남긴다() {
        failed(school, junho, DATE, "블록 놀이를 했다.");

        service.retry(TEACHER, junho, DATE);

        // 꺼져 있으면 요청이 있는 묶음만 요약하므로, 남기지 않으면 [다시 요약]을 눌러도 멈춰 있다.
        assertThat(runRequests.isRequested(new SummaryGroup(junho, DATE, ORG))).isTrue();
    }

    @Test
    void 자동_요약이_켜져_있으면_다시_요약할_때_요약_요청을_남기지_않는다() {
        given(summaryProperties.autoRun()).willReturn(true);
        failed(school, junho, DATE, "블록 놀이를 했다.");

        service.retry(TEACHER, junho, DATE);

        assertThat(runRequests.isRequested(new SummaryGroup(junho, DATE, ORG))).isFalse();
    }

    @Test
    void 요약_없이_마무리하면_그_묶음의_요약_실패_일지가_끝난다() {
        JournalEntry target = failed(school, junho, DATE, "블록 놀이를 했다.");
        JournalEntry otherChild = failed(school, seojun, DATE, "그림을 그렸다.");

        service.close(TEACHER, junho, DATE);

        assertThat(statusOf(target)).isEqualTo(JournalEntryStatus.COMPLETED);
        assertThat(statusOf(otherChild)).isEqualTo(JournalEntryStatus.SUMMARY_FAILED);
        assertThat(service.getFailedGroups(TEACHER)).extracting(FailedSummaryGroupResponse::childId)
                .containsExactly(seojun);
    }

    @Test
    void 요약_실패_일지가_없는_묶음은_찾을_수_없다() {
        // 다른 창에서 이미 처리했거나, 다른 기관 일지다.
        consent(junho, OTHER_ORG);
        failed(center, junho, DATE, "센터 기록");

        assertThatThrownBy(() -> service.retry(TEACHER, junho, DATE))
                .isInstanceOfSatisfying(SummaryException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SummaryErrorCode.FAILED_NOT_FOUND));
        assertThatThrownBy(() -> service.close(TEACHER, junho, DATE))
                .isInstanceOfSatisfying(SummaryException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SummaryErrorCode.FAILED_NOT_FOUND));
    }

    @Test
    void 우리_기관_동의가_없는_아이는_다시_요약하거나_마무리하지_않는다() {
        Long minji = em.persist(Child.of("박민지", LocalDate.of(2020, 7, 1))).getId();
        JournalEntry entry = failed(school, minji, DATE, "동의 없음");

        assertThatThrownBy(() -> service.retry(TEACHER, minji, DATE))
                .isInstanceOf(SummaryChildNotFoundException.class);
        assertThatThrownBy(() -> service.close(TEACHER, minji, DATE))
                .isInstanceOf(SummaryChildNotFoundException.class);
        assertThat(statusOf(entry)).isEqualTo(JournalEntryStatus.SUMMARY_FAILED);
    }
}
