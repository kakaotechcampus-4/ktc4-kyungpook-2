package com.itda.backend.service.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.matching.MatchingResult;
import com.itda.backend.domain.matching.MatchingStatus;
import com.itda.backend.domain.record.FailureStage;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.validation.ValidationResult;
import com.itda.backend.domain.validation.ValidationVerdict;
import com.itda.backend.dto.response.child.PendingLinkRequestResponse;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse.Kind;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse.Row;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse.Status;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse.Target;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse.Turn;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse.Week;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse.Who;
import com.itda.backend.dto.response.insight.Gate2InsightResponse;
import com.itda.backend.dto.response.insight.InsightProgressResponse;
import com.itda.backend.dto.response.summary.Gate1SummaryResponse;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.matching.MatchingResultRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.record.PipelineEntry;
import com.itda.backend.repository.validation.ValidationResultRepository;
import com.itda.backend.service.approval.SummaryGate1QueryService;
import com.itda.backend.service.child.InstitutionLinkService;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.insight.InsightGate2QueryService;
import com.itda.backend.service.insight.InsightProgressService;
import com.itda.backend.service.insight.InsightWeekStageReader;
import com.itda.backend.service.insight.InsightWeekStageReader.Stage;
import com.itda.backend.service.insight.InsightWeekStageReader.WeekStage;
import com.itda.backend.service.user.UserService;

/** 대시보드 진행 현황 (#370). 줄 규칙은 시안 {@code rows()} 와 plan.md B-1 표를 따른다. */
@ExtendWith(MockitoExtension.class)
class DashboardPipelineServiceTest {

    private static final String TEACHER = "99";
    private static final Long ORG = 3L;
    private static final LocalDate DATE = LocalDate.of(2026, 11, 5);
    private static final Long JUNHO = 8L;
    private static final Long SEOAH = 9L;

    @Mock
    private UserService userService;
    @Mock
    private JournalEntryRepository journalEntryRepository;
    @Mock
    private MatchingResultRepository matchingResultRepository;
    @Mock
    private ValidationResultRepository validationResultRepository;
    @Mock
    private ChildOrganizationRepository childOrganizationRepository;
    @Mock
    private ConsentStateReader consentStateReader;
    @Mock
    private ChildRepository childRepository;
    @Mock
    private SummaryGate1QueryService summaryGate1QueryService;
    @Mock
    private InsightProgressService insightProgressService;
    @Mock
    private InsightWeekStageReader insightWeekStageReader;
    @Mock
    private InsightGate2QueryService insightGate2QueryService;
    @Mock
    private InstitutionLinkService institutionLinkService;

    private DashboardPipelineService service;

    private final List<PipelineEntry> entries = new ArrayList<>();
    private final Set<Long> members = new HashSet<>(Set.of(JUNHO));

    @BeforeEach
    void setUp() {
        service = new DashboardPipelineService(userService, journalEntryRepository, matchingResultRepository,
                validationResultRepository, childOrganizationRepository, consentStateReader, childRepository,
                summaryGate1QueryService, insightProgressService, insightWeekStageReader, insightGate2QueryService,
                institutionLinkService);
        given(userService.getOrganizationIdOf(TEACHER)).willReturn(ORG);
        given(journalEntryRepository.findPipelineEntries(eq("3"), anyCollection())).willReturn(entries);
        lenient().when(childOrganizationRepository.findByOrganizationIdAndDeletedAtIsNull(ORG))
                .thenAnswer(inv -> members.stream().map(id -> ChildOrganization.of(id, ORG)).toList());
        lenient().when(consentStateReader.grantedChildIds(anyCollection(), eq(ORG)))
                .thenAnswer(inv -> new HashSet<>(inv.<Collection<Long>>getArgument(0)));
        lenient().when(childRepository.findAllById(any())).thenReturn(List.of(child(JUNHO, "김준호"),
                child(SEOAH, "이서아")));
        lenient().when(summaryGate1QueryService.getSummaries(TEACHER, "pending")).thenReturn(List.of());
        lenient().when(summaryGate1QueryService.getSummaries(TEACHER, "hold")).thenReturn(List.of());
        lenient().when(insightProgressService.getProgress(TEACHER)).thenReturn(List.of());
        lenient().when(insightWeekStageReader.stagesOf(eq(ORG), anyCollection())).thenReturn(Map.of());
        lenient().when(insightGate2QueryService.getInsights(TEACHER, "pending")).thenReturn(List.of());
        lenient().when(institutionLinkService.pendingRequests(TEACHER)).thenReturn(List.of());
    }

    @Test
    void 아무_일도_없으면_줄도_건수도_없다() {
        DashboardPipelineResponse response = service.getPipeline(TEACHER);

        assertThat(response.rows()).isEmpty();
        assertThat(response.turn()).isEqualTo(new Turn(0, 0, 0, 0, 0));
    }

    @Test
    void 아이를_찾는_중인_일지는_날짜마다_한_줄로_모아_센다() {
        entry(1L, null, DATE, JournalEntryStatus.PENDING);
        entry(2L, null, DATE, JournalEntryStatus.MATCHING);
        entry(3L, null, DATE.plusDays(1), JournalEntryStatus.PENDING);

        List<Row> rows = service.getPipeline(TEACHER).rows();

        assertThat(rows).extracting(Row::kind, Row::childId, Row::date, Row::step, Row::who, Row::status)
                .containsExactly(
                        tuple(Kind.ENTRY, null, DATE, 0, Who.AI, Status.MATCHING),
                        tuple(Kind.ENTRY, null, DATE.plusDays(1), 0, Who.AI, Status.MATCHING));
        assertThat(rows.get(0).detail().count()).isEqualTo(2);
        assertThat(rows.get(0).target()).isNull();
    }

    @Test
    void 아이_확인_대기_일지는_일지마다_내_차례이고_최신_매칭_결과를_연다() {
        entry(1L, null, DATE, JournalEntryStatus.MATCH_REVIEW);
        given(matchingResultRepository.findByJournalEntryIdIn(anyCollection()))
                .willReturn(List.of(matching(5L, 1L), matching(9L, 1L)));

        DashboardPipelineResponse response = service.getPipeline(TEACHER);

        assertThat(response.rows()).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo(Status.MATCH_REVIEW);
            assertThat(row.who()).isEqualTo(Who.ME);
            assertThat(row.target()).isEqualTo(new Target("matching", "9"));
        });
        assertThat(response.turn().confirm()).isEqualTo(1);
    }

    @Test
    void 막힌_일지는_이유와_함께_수정_요청_항목을_연다() {
        entry(1L, JUNHO, DATE, JournalEntryStatus.VALIDATION_BLOCKED);
        given(validationResultRepository.findByJournalEntryIdInOrderByIdDesc(anyCollection()))
                .willReturn(List.of(validation(30L, 1L, "[\"개인정보표현\"]"), validation(20L, 1L, "[\"진단명\"]")));

        DashboardPipelineResponse response = service.getPipeline(TEACHER);

        assertThat(response.rows()).singleElement().satisfies(row -> {
            assertThat(row.childName()).isEqualTo("김준호");
            assertThat(row.step()).isEqualTo(1);
            assertThat(row.status()).isEqualTo(Status.BLOCKED);
            assertThat(row.detail().kinds()).containsExactly("개인정보표현");
            assertThat(row.detail().reasons()).hasSize(1);
            assertThat(row.target()).isEqualTo(new Target("validation", "30"));
        });
        assertThat(response.turn().blocked()).isEqualTo(1);
    }

    @Test
    void 검증을_기다리거나_검증_중인_묶음은_AI_확인_단계다() {
        entry(1L, JUNHO, DATE, JournalEntryStatus.MATCHED);
        entry(2L, JUNHO, DATE, JournalEntryStatus.VALIDATING);

        assertThat(service.getPipeline(TEACHER).rows())
                .extracting(Row::childId, Row::childName, Row::step, Row::who, Row::status)
                .containsExactly(tuple("8", "김준호", 1, Who.AI, Status.VALIDATING));
    }

    @Test
    void 같은_날짜에_처리_중인_일지가_있으면_요약을_기다리고_없으면_요약_중이다() {
        entry(1L, JUNHO, DATE, JournalEntryStatus.VALIDATED);
        entry(2L, null, DATE, JournalEntryStatus.PENDING);
        entry(3L, JUNHO, DATE.plusDays(1), JournalEntryStatus.VALIDATED);

        assertThat(service.getPipeline(TEACHER).rows())
                .extracting(Row::childId, Row::date, Row::who, Row::status)
                .containsExactly(
                        tuple(null, DATE, Who.AI, Status.MATCHING),
                        tuple("8", DATE.plusDays(1), Who.AI, Status.SUMMARIZING),
                        tuple("8", DATE, Who.WAIT, Status.WAITING_SAME_DATE));
    }

    @Test
    void 요약에_실패한_묶음은_내_차례이고_검토_건수에_든다() {
        entry(1L, JUNHO, DATE, JournalEntryStatus.SUMMARY_FAILED);
        entry(2L, JUNHO, DATE, JournalEntryStatus.SUMMARY_FAILED);

        DashboardPipelineResponse response = service.getPipeline(TEACHER);

        assertThat(response.rows()).singleElement().satisfies(row -> {
            assertThat(row.step()).isEqualTo(2);
            assertThat(row.who()).isEqualTo(Who.ME);
            assertThat(row.status()).isEqualTo(Status.SUMMARY_FAILED);
            assertThat(row.target()).isEqualTo(new Target("summary_failed", null));
        });
        assertThat(response.turn().review()).isEqualTo(1);
    }

    @Test
    void 시스템_오류는_실패한_단계에서_관리자_차례로_보인다() {
        entry(1L, JUNHO, DATE, JournalEntryStatus.SYSTEM_ERROR, FailureStage.VALIDATION);
        entry(2L, JUNHO, DATE.plusDays(1), JournalEntryStatus.SYSTEM_ERROR, FailureStage.SUMMARY);

        assertThat(service.getPipeline(TEACHER).rows())
                .extracting(Row::date, Row::step, Row::who, Row::status)
                .containsExactly(
                        tuple(DATE, 1, Who.OTHER, Status.SYSTEM_ERROR),
                        tuple(DATE.plusDays(1), 2, Who.OTHER, Status.SYSTEM_ERROR));
    }

    @Test
    void 검토_대기_요약은_내_차례고_다시_만드는_중이면_AI_차례다() {
        given(summaryGate1QueryService.getSummaries(TEACHER, "pending")).willReturn(List.of(
                gate1(50L, JUNHO, DATE, false, true), gate1(51L, SEOAH, DATE, true, false)));
        given(summaryGate1QueryService.getSummaries(TEACHER, "hold")).willReturn(List.of(
                gate1(52L, JUNHO, DATE.minusDays(1), false, false)));

        DashboardPipelineResponse response = service.getPipeline(TEACHER);

        assertThat(response.rows())
                .extracting(Row::childName, Row::step, Row::who, Row::status, Row::target)
                .containsExactly(
                        tuple("김준호", 3, Who.ME, Status.REVIEW_HELD, new Target("summary", "52")),
                        tuple("김준호", 3, Who.ME, Status.REVIEW, new Target("summary", "50")),
                        tuple("이서아", 3, Who.AI, Status.REGENERATING, null));
        assertThat(response.rows().get(1).detail().noContent()).isTrue();
        // 다시 만드는 중인 요약은 지금 열어도 409 가 나므로 선생님 차례에서 뺀다.
        assertThat(response.turn().review()).isEqualTo(1);
    }

    @Test
    void 검토_대기_요약이_있는_묶음의_새_일지는_그_요약을_다시_만드는_것이라_따로_줄을_만들지_않는다() {
        given(summaryGate1QueryService.getSummaries(TEACHER, "pending"))
                .willReturn(List.of(gate1(50L, JUNHO, DATE, true, false)));
        entry(1L, JUNHO, DATE, JournalEntryStatus.VALIDATING);

        assertThat(service.getPipeline(TEACHER).rows()).extracting(Row::status)
                .containsExactly(Status.REGENERATING);
    }

    @Test
    void 검토_대기_요약이_있어도_새_일지의_요약이_실패하면_그_실패도_내_차례로_보인다() {
        // 다시 요약이 실패하면 원래 일지는 검토 대기로 돌아가고 새 일지만 SUMMARY_FAILED 로 남는다.
        given(summaryGate1QueryService.getSummaries(TEACHER, "pending"))
                .willReturn(List.of(gate1(50L, JUNHO, DATE, false, false)));
        entry(1L, JUNHO, DATE, JournalEntryStatus.SUMMARY_FAILED);

        DashboardPipelineResponse response = service.getPipeline(TEACHER);

        assertThat(response.rows()).extracting(Row::status)
                .containsExactly(Status.SUMMARY_FAILED, Status.REVIEW);
        assertThat(response.turn().review()).isEqualTo(2);
    }

    @Test
    void 보류한_요약이_있어도_새_일지는_새_판으로_요약되니_따로_보인다() {
        given(summaryGate1QueryService.getSummaries(TEACHER, "hold"))
                .willReturn(List.of(gate1(52L, JUNHO, DATE, false, false)));
        entry(1L, JUNHO, DATE, JournalEntryStatus.SUMMARIZING);

        assertThat(service.getPipeline(TEACHER).rows()).extracting(Row::status)
                .containsExactly(Status.REVIEW_HELD, Status.SUMMARIZING);
    }

    @Test
    void 명부에서_빠졌거나_동의가_꺼진_아이의_묶음은_보이지_않는다() {
        entry(1L, SEOAH, DATE, JournalEntryStatus.VALIDATED);
        entry(2L, SEOAH, DATE, JournalEntryStatus.SYSTEM_ERROR, FailureStage.SUMMARY);

        assertThat(service.getPipeline(TEACHER).rows()).isEmpty();
    }

    @Test
    void 검토를_승인한_뒤에는_아이와_주로_한_줄이고_분석_상태를_따른다() {
        given(insightProgressService.getProgress(TEACHER)).willReturn(List.of(
                progress(JUNHO, "김준호", "2026-11-02", "2026-11-08", InsightProgressResponse.Status.WAITING_WEEK_END),
                progress(JUNHO, "김준호", "2026-10-26", "2026-11-01", InsightProgressResponse.Status.RUNNING),
                progress(SEOAH, "이서아", "2026-10-26", "2026-11-01", InsightProgressResponse.Status.NOT_CONSENTED),
                progress(SEOAH, "이서아", "2026-10-19", "2026-10-25", InsightProgressResponse.Status.NOTHING_NEW)));

        List<Row> rows = service.getPipeline(TEACHER).rows();

        assertThat(rows).extracting(Row::kind, Row::childName, Row::step, Row::who, Row::status)
                .containsExactly(
                        tuple(Kind.WEEK, "김준호", 4, Who.AI, Status.RUNNING),
                        tuple(Kind.WEEK, "김준호", 4, Who.WAIT, Status.WAITING_WEEK_END),
                        tuple(Kind.WEEK, "이서아", 4, Who.OTHER, Status.NOT_CONSENTED),
                        tuple(Kind.WEEK, "이서아", 4, Who.DONE, Status.NOTHING_NEW));
        assertThat(rows.get(0).week())
                .isEqualTo(new Week(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1), "2026년 10월 4주차"));
        assertThat(rows.get(0).date()).isNull();
    }

    @Test
    void 분석이_끝난_주는_누가_승인할_차례인지_보여준다() {
        given(insightProgressService.getProgress(TEACHER)).willReturn(List.of(
                progress(JUNHO, "김준호", "2026-10-26", "2026-11-01", InsightProgressResponse.Status.DONE),
                progress(SEOAH, "이서아", "2026-10-26", "2026-11-01", InsightProgressResponse.Status.DONE),
                progress(JUNHO, "김준호", "2026-10-19", "2026-10-25", InsightProgressResponse.Status.DONE),
                progress(SEOAH, "이서아", "2026-10-19", "2026-10-25", InsightProgressResponse.Status.DONE),
                progress(JUNHO, "김준호", "2026-10-12", "2026-10-18", InsightProgressResponse.Status.DONE)));
        LocalDate from = LocalDate.of(2026, 10, 26);
        LocalDate to = LocalDate.of(2026, 11, 1);
        given(insightWeekStageReader.stagesOf(eq(ORG), anyCollection())).willReturn(Map.of(
                week(JUNHO, from, to), new WeekStage(Stage.REVIEW_HERE, 70L, null),
                week(SEOAH, from, to), new WeekStage(Stage.REVIEW_ELSEWHERE, null, "해솔초등학교"),
                week(JUNHO, from.minusWeeks(1), to.minusWeeks(1)), new WeekStage(Stage.INSIGHT_HELD, 71L, null),
                week(SEOAH, from.minusWeeks(1), to.minusWeeks(1)), new WeekStage(Stage.APPROVED, null, null)));

        List<Row> rows = service.getPipeline(TEACHER).rows();

        assertThat(rows).extracting(Row::childName, Row::step, Row::who, Row::status, Row::target)
                .containsExactly(
                        tuple("김준호", 5, Who.ME, Status.INSIGHT_HELD, new Target("insight", "71")),
                        tuple("김준호", 5, Who.ME, Status.REVIEW_HERE, new Target("insight", "70")),
                        tuple("이서아", 5, Who.OTHER, Status.REVIEW_ELSEWHERE, null),
                        // 우리 기록이 쓰인 글이 없는 주는 우리에게 새로 알릴 것이 없다.
                        tuple("김준호", 4, Who.DONE, Status.NOTHING_NEW, null),
                        tuple("이서아", 5, Who.DONE, Status.APPROVED, null));
        assertThat(rows.get(2).detail().approverName()).isEqualTo("해솔초등학교");
    }

    @Test
    void 분석_승인과_연결_요청_건수는_그_목록과_같다() {
        given(insightGate2QueryService.getInsights(TEACHER, "pending"))
                .willReturn(List.of(mock(Gate2InsightResponse.class), mock(Gate2InsightResponse.class)));
        given(institutionLinkService.pendingRequests(TEACHER))
                .willReturn(List.of(mock(PendingLinkRequestResponse.class)));

        assertThat(service.getPipeline(TEACHER).turn()).isEqualTo(new Turn(0, 0, 0, 2, 1));
    }

    @Test
    void 내_차례_AI_대기_다른_사람_끝_순서로_그다음_단계와_날짜_순으로_놓는다() {
        entry(1L, JUNHO, DATE, JournalEntryStatus.SYSTEM_ERROR, FailureStage.SUMMARY);
        entry(2L, JUNHO, DATE.plusDays(1), JournalEntryStatus.VALIDATED);
        entry(3L, null, DATE.plusDays(1), JournalEntryStatus.MATCHING);
        entry(4L, null, DATE.plusDays(2), JournalEntryStatus.MATCH_REVIEW);
        entry(5L, JUNHO, DATE, JournalEntryStatus.VALIDATING);
        given(matchingResultRepository.findByJournalEntryIdIn(anyCollection())).willReturn(List.of());

        assertThat(service.getPipeline(TEACHER).rows()).extracting(Row::who, Row::step, Row::date)
                .containsExactly(
                        tuple(Who.ME, 0, DATE.plusDays(2)),
                        tuple(Who.AI, 0, DATE.plusDays(1)),
                        tuple(Who.AI, 1, DATE),
                        tuple(Who.WAIT, 2, DATE.plusDays(1)),
                        tuple(Who.OTHER, 2, DATE));
    }

    private void entry(Long id, Long childId, LocalDate date, JournalEntryStatus status) {
        entry(id, childId, date, status, null);
    }

    private void entry(Long id, Long childId, LocalDate date, JournalEntryStatus status, FailureStage stage) {
        entries.add(new PipelineEntry(id, childId, date, status, stage));
    }

    private static Child child(Long id, String name) {
        Child child = Child.of(name, LocalDate.of(2020, 3, 1));
        ReflectionTestUtils.setField(child, "id", id);
        return child;
    }

    private static MatchingResult matching(Long id, Long entryId) {
        MatchingResult result = new MatchingResult(entryId, null, null, MatchingStatus.REVIEW, null, null, null,
                null, null, null, null);
        ReflectionTestUtils.setField(result, "id", id);
        return result;
    }

    private static ValidationResult validation(Long id, Long entryId, String issueTypes) {
        ValidationResult result = ValidationResult.of(entryId, null, JUNHO, ValidationVerdict.BLOCK, issueTypes,
                "[]", "{}");
        ReflectionTestUtils.setField(result, "id", id);
        return result;
    }

    private static Gate1SummaryResponse gate1(Long id, Long childId, LocalDate date, boolean processing,
            boolean noContent) {
        return new Gate1SummaryResponse(id, childId, childId.equals(JUNHO) ? "김준호" : "이서아", date, 1,
                "GATE1_PENDING", "v1", processing, "요약", List.of(), noContent, false, List.of(), List.of(),
                List.of(), List.of());
    }

    private static InsightProgressResponse progress(Long childId, String name, String from, String to,
            InsightProgressResponse.Status status) {
        return InsightProgressResponse.of(childId, name, from, to,
                "2026년 " + Integer.parseInt(from.substring(5, 7)) + "월 "
                        + ((Integer.parseInt(from.substring(8)) - 1) / 7 + 1) + "주차", status);
    }

    private static InsightWeekStageReader.Week week(Long childId, LocalDate from, LocalDate to) {
        return new InsightWeekStageReader.Week(childId, from, to);
    }
}
