package com.itda.backend.service.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
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
import com.itda.backend.domain.record.FailureReason;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.record.RawRecordStatus;
import com.itda.backend.domain.summary.SummaryRunRequest;
import com.itda.backend.dto.response.summary.SummaryGroupBoardResponse;
import com.itda.backend.dto.response.summary.SummaryGroupBoardResponse.Counts;
import com.itda.backend.dto.response.summary.SummaryGroupBoardResponse.MatchingCounts;
import com.itda.backend.dto.response.summary.SummaryGroupBoardResponse.SummaryGroupCard;
import com.itda.backend.service.user.UserService;

/** 오늘의 업무 묶음 카드 — 요약 전 묶음만, 할 일 우선 라벨과 상태별 건수, 요약 요청·대기 표시. */
@DataJpaTest
@ActiveProfiles("test")
@Import({SummaryGroupQueryService.class, SummaryRunRequests.class})
class SummaryGroupQueryServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 8);
    private static final Long ORG = 3L;
    private static final Long OTHER_ORG = 4L;
    private static final String TEACHER = "99";

    @Autowired
    private TestEntityManager em;

    @Autowired
    private SummaryGroupQueryService service;

    @MockitoBean
    private UserService userService;

    private Long junho;
    private Long seoa;
    private Long ourFile;
    private Long otherFile;

    @BeforeEach
    void setUp() {
        given(userService.getOrganizationIdOf(TEACHER)).willReturn(ORG);
        junho = em.persist(Child.of("김준호", LocalDate.of(2020, 3, 1))).getId();
        seoa = em.persist(Child.of("한서아", LocalDate.of(2019, 7, 15))).getId();
        ourFile = file(ORG);
        otherFile = file(OTHER_ORG);
    }

    private Long file(Long institutionId) {
        return em.persist(new RawRecord(String.valueOf(institutionId), "school.pdf", "kept/school.pdf",
                "application/pdf", 12, RawRecordStatus.PENDING)).getId();
    }

    /** 우리 기관 파일에서 나온 일지를 그 상태까지 보낸다. */
    private JournalEntry entry(Long child, LocalDate date, JournalEntryStatus target) {
        return entry(ourFile, child, date, target);
    }

    /** 워커·교사가 지나가는 순서대로 상태를 바꿔 {@code target} 에 멈춘다. */
    private JournalEntry entry(Long rawRecordId, Long child, LocalDate date, JournalEntryStatus target) {
        JournalEntry e = JournalEntry.of(rawRecordId, date, "일지", 1);
        switch (target) {
            case PENDING -> { }
            case MATCHING -> e.startMatching();
            case MATCH_REVIEW -> {
                e.startMatching();
                e.requestMatchReview();
            }
            case FAILED -> {
                e.startMatching();
                e.failMatching();
            }
            default -> {
                e.startMatching();
                e.confirmMatch(child);
                if (target != JournalEntryStatus.MATCHED) {
                    advanceFromMatched(e, target);
                }
            }
        }
        return em.persistAndFlush(e);
    }

    private static void advanceFromMatched(JournalEntry e, JournalEntryStatus target) {
        e.startValidating();
        switch (target) {
            case VALIDATING -> { }
            case VALIDATION_BLOCKED -> e.blockValidation();
            case VALIDATION_HELD -> {
                e.blockValidation();
                e.holdAfterValidation();
            }
            case SYSTEM_ERROR -> e.failValidation(FailureReason.RESPONSE_UNREADABLE);
            default -> {
                e.passValidation();
                if (target == JournalEntryStatus.VALIDATED) {
                    return;
                }
                e.startSummarizing();
                switch (target) {
                    case SUMMARIZING -> { }
                    case GATE1_PENDING -> e.completeSummary(999L);
                    case SUMMARY_FAILED -> e.failSummary();
                    default -> throw new IllegalArgumentException("테스트가 다루지 않는 상태: " + target);
                }
            }
        }
    }

    private void requested(Long child, LocalDate date) {
        em.persistAndFlush(SummaryRunRequest.of(child, date, ORG, date.atTime(17, 0)));
    }

    private SummaryGroupCard onlyCard() {
        List<SummaryGroupCard> groups = service.getBoard(TEACHER).groups();
        assertThat(groups).hasSize(1);
        return groups.get(0);
    }

    @Test
    void 우리_기관의_아이_날짜_묶음만_카드로_보여준다() {
        entry(junho, DATE, JournalEntryStatus.VALIDATED);
        entry(otherFile, junho, DATE.plusDays(1), JournalEntryStatus.VALIDATED);

        SummaryGroupCard card = onlyCard();

        assertThat(card.childId()).isEqualTo(junho);
        assertThat(card.childName()).isEqualTo("김준호");
        assertThat(card.birthdate()).isEqualTo(LocalDate.of(2020, 3, 1));
        assertThat(card.entryDate()).isEqualTo(DATE);
    }

    @Test
    void 날짜를_모르는_일지는_카드를_만들지_않는다() {
        entry(junho, null, JournalEntryStatus.VALIDATED);

        assertThat(service.getBoard(TEACHER).groups()).isEmpty();
    }

    @Test
    void 상태별_건수를_함께_준다() {
        entry(junho, DATE, JournalEntryStatus.MATCHED);
        entry(junho, DATE, JournalEntryStatus.VALIDATING);
        entry(junho, DATE, JournalEntryStatus.VALIDATION_BLOCKED);
        entry(junho, DATE, JournalEntryStatus.VALIDATED);
        entry(junho, DATE, JournalEntryStatus.VALIDATED);
        entry(junho, DATE, JournalEntryStatus.SYSTEM_ERROR);

        assertThat(onlyCard().counts()).isEqualTo(new Counts(2, 1, 2, 0, 1));
    }

    @Test
    void 요약_대기만_있으면_요약할_수_있다() {
        entry(junho, DATE, JournalEntryStatus.VALIDATED);

        SummaryGroupCard card = onlyCard();

        assertThat(card.status()).isEqualTo("ready");
        assertThat(card.canSummarize()).isTrue();
        assertThat(card.summaryRequested()).isFalse();
    }

    @Test
    void 검증_중이_섞여도_요약_대기가_있으면_요약할_수_있다() {
        entry(junho, DATE, JournalEntryStatus.VALIDATED);
        entry(junho, DATE, JournalEntryStatus.VALIDATING);

        SummaryGroupCard card = onlyCard();

        assertThat(card.status()).isEqualTo("ready");
        assertThat(card.canSummarize()).isTrue();
    }

    @Test
    void 수정_요청이_섞이면_수정_필요가_먼저지만_요약은_할_수_있다() {
        entry(junho, DATE, JournalEntryStatus.VALIDATED);
        entry(junho, DATE, JournalEntryStatus.VALIDATION_BLOCKED);

        SummaryGroupCard card = onlyCard();

        assertThat(card.status()).isEqualTo("needs_fix");
        assertThat(card.canSummarize()).isTrue();
    }

    @Test
    void 수정_요청만_있으면_요약할_수_없다() {
        entry(junho, DATE, JournalEntryStatus.VALIDATION_BLOCKED);

        SummaryGroupCard card = onlyCard();

        assertThat(card.status()).isEqualTo("needs_fix");
        assertThat(card.canSummarize()).isFalse();
    }

    @Test
    void 검증_중만_있으면_검증_중이다() {
        entry(junho, DATE, JournalEntryStatus.MATCHED);

        SummaryGroupCard card = onlyCard();

        assertThat(card.status()).isEqualTo("validating");
        assertThat(card.canSummarize()).isFalse();
    }

    @Test
    void 요약_중인_일지가_있으면_요약_중이다() {
        entry(junho, DATE, JournalEntryStatus.SUMMARIZING);

        assertThat(onlyCard().status()).isEqualTo("summarizing");
    }

    @Test
    void 요약을_눌러_뒀으면_아직_시작_전이어도_요약_중이고_다시_누를_수_없다() {
        entry(junho, DATE, JournalEntryStatus.VALIDATED);
        requested(junho, DATE);

        SummaryGroupCard card = onlyCard();

        assertThat(card.status()).isEqualTo("summarizing");
        assertThat(card.summaryRequested()).isTrue();
        assertThat(card.canSummarize()).isFalse();
    }

    @Test
    void 요약을_눌러_뒀는데_같은_날_처리_중인_일지가_있으면_그_건수를_알려준다() {
        entry(junho, DATE, JournalEntryStatus.VALIDATED);
        requested(junho, DATE);
        entry(seoa, DATE, JournalEntryStatus.VALIDATING);
        entry(null, DATE, JournalEntryStatus.MATCHING);
        entry(null, DATE.plusDays(1), JournalEntryStatus.MATCHING);

        SummaryGroupCard card = service.getBoard(TEACHER).groups().stream()
                .filter(c -> c.childId().equals(junho)).findFirst().orElseThrow();

        assertThat(card.status()).isEqualTo("summarizing");
        assertThat(card.waitingCount()).isEqualTo(2);
    }

    @Test
    void 누르지_않았으면_기다리는_건수는_0이다() {
        entry(junho, DATE, JournalEntryStatus.VALIDATED);
        entry(null, DATE, JournalEntryStatus.MATCHING);

        assertThat(onlyCard().waitingCount()).isZero();
    }

    @Test
    void 시스템_오류만_남으면_관리자_확인_중이다() {
        entry(junho, DATE, JournalEntryStatus.SYSTEM_ERROR);

        SummaryGroupCard card = onlyCard();

        assertThat(card.status()).isEqualTo("admin_check");
        assertThat(card.canSummarize()).isFalse();
    }

    @Test
    void 요약이_만들어진_묶음은_카드가_없다() {
        entry(junho, DATE, JournalEntryStatus.GATE1_PENDING);

        assertThat(service.getBoard(TEACHER).groups()).isEmpty();
    }

    @Test
    void 요약_뒤에_새_일지가_들어오면_1차_검토에_이전_요약이_있다고_알려준다() {
        entry(junho, DATE, JournalEntryStatus.GATE1_PENDING);
        entry(junho, DATE, JournalEntryStatus.VALIDATED);

        SummaryGroupCard card = onlyCard();

        assertThat(card.previousSummaryInReview()).isTrue();
        assertThat(card.counts().ready()).isEqualTo(1);
    }

    @Test
    void 요약_실패와_수정_요청에서_끝낸_일지는_카드를_만들지_않는다() {
        entry(junho, DATE, JournalEntryStatus.SUMMARY_FAILED);
        entry(junho, DATE, JournalEntryStatus.VALIDATION_HELD);

        assertThat(service.getBoard(TEACHER).groups()).isEmpty();
    }

    @Test
    void 오래된_날짜부터_보여준다() {
        entry(seoa, DATE.plusDays(1), JournalEntryStatus.VALIDATED);
        entry(seoa, DATE, JournalEntryStatus.VALIDATED);
        entry(junho, DATE, JournalEntryStatus.VALIDATED);

        assertThat(service.getBoard(TEACHER).groups())
                .extracting(SummaryGroupCard::entryDate, SummaryGroupCard::childId)
                .containsExactly(
                        tuple(DATE, junho),
                        tuple(DATE, seoa),
                        tuple(DATE.plusDays(1), seoa));
    }

    @Test
    void 아이를_정하는_중인_일지와_확인_필요_일지_건수를_준다() {
        entry(null, DATE, JournalEntryStatus.PENDING);
        entry(null, DATE, JournalEntryStatus.MATCHING);
        entry(null, null, JournalEntryStatus.MATCHING);
        entry(null, DATE, JournalEntryStatus.MATCH_REVIEW);
        entry(null, DATE, JournalEntryStatus.FAILED);
        entry(otherFile, null, DATE, JournalEntryStatus.MATCHING);

        assertThat(service.getBoard(TEACHER).matching()).isEqualTo(new MatchingCounts(3, 2));
    }
}
