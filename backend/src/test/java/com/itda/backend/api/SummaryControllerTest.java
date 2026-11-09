package com.itda.backend.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.itda.backend.dto.request.summary.Gate1DecisionRequest.Card;
import com.itda.backend.dto.response.summary.FailedSummaryGroupResponse;
import com.itda.backend.dto.response.summary.Gate1DecisionResponse;
import com.itda.backend.dto.response.summary.Gate1SummaryResponse;
import com.itda.backend.dto.response.summary.SummaryGroupBoardResponse;
import com.itda.backend.dto.response.summary.SummaryGroupBoardResponse.Counts;
import com.itda.backend.dto.response.summary.SummaryGroupBoardResponse.MatchingCounts;
import com.itda.backend.dto.response.summary.SummaryGroupBoardResponse.SummaryGroupCard;
import com.itda.backend.exception.summary.SummaryChildNotFoundException;
import com.itda.backend.exception.summary.SummaryException;
import com.itda.backend.exception.summary.SummaryErrorCode;
import com.itda.backend.exception.summary.SummaryRunValidationException;
import com.itda.backend.domain.approval.Gate1Action;
import com.itda.backend.service.approval.SummaryGate1QueryService;
import com.itda.backend.service.approval.SummaryGate1Service;
import com.itda.backend.service.summary.SummaryFailureService;
import com.itda.backend.service.summary.SummaryGroupQueryService;
import com.itda.backend.service.summary.SummaryRunService;

@WebMvcTest(SummaryController.class)
@AutoConfigureMockMvc(addFilters = false)
class SummaryControllerTest {

    private static final String RUN_URL = "/api/v1/summaries/run";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SummaryRunService summaryRunService;

    @MockitoBean
    private SummaryGate1Service summaryGate1Service;

    @MockitoBean
    private SummaryGate1QueryService summaryGate1QueryService;

    @MockitoBean
    private SummaryGroupQueryService summaryGroupQueryService;

    @MockitoBean
    private SummaryFailureService summaryFailureService;

    @Test
    void groups_returnsMatchingCountsAndCards() throws Exception {
        given(summaryGroupQueryService.getBoard(any())).willReturn(new SummaryGroupBoardResponse(
                new MatchingCounts(5, 2),
                List.of(new SummaryGroupCard(12L, "김하늘", LocalDate.of(2019, 3, 2), LocalDate.of(2026, 9, 17),
                        "ready", new Counts(1, 0, 2, 0, 0), true, false, 0, true))));

        mockMvc.perform(get("/api/v1/summaries/groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.matching.inProgress").value(5))
                .andExpect(jsonPath("$.data.matching.needsReview").value(2))
                .andExpect(jsonPath("$.data.groups[0].childId").value(12))
                .andExpect(jsonPath("$.data.groups[0].childName").value("김하늘"))
                .andExpect(jsonPath("$.data.groups[0].birthdate").value("2019-03-02"))
                .andExpect(jsonPath("$.data.groups[0].entryDate").value("2026-09-17"))
                .andExpect(jsonPath("$.data.groups[0].status").value("ready"))
                .andExpect(jsonPath("$.data.groups[0].counts.ready").value(2))
                .andExpect(jsonPath("$.data.groups[0].canSummarize").value(true))
                .andExpect(jsonPath("$.data.groups[0].summaryRequested").value(false))
                .andExpect(jsonPath("$.data.groups[0].waitingCount").value(0))
                .andExpect(jsonPath("$.data.groups[0].previousSummaryInReview").value(true));
    }

    @Test
    void run_requestsSummaryForChildAndDate() throws Exception {
        mockMvc.perform(post(RUN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"childId\": 8, \"entryDate\": \"2026-10-08\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"));

        verify(summaryRunService).requestRun(any(), eq(8L), eq(LocalDate.of(2026, 10, 8)));
    }

    @Test
    void run_rejectsMissingOrInvalidDate() throws Exception {
        for (String body : new String[] {
                "{\"childId\": 8}",
                "{\"childId\": 8, \"entryDate\": \"2026-02-30\"}",
                "{\"childId\": 8, \"entryDate\": \"20261008\"}",
                "{\"entryDate\": \"2026-10-08\"}"}) {
            mockMvc.perform(post(RUN_URL).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }

        verify(summaryRunService, never()).requestRun(any(), any(), any());
    }

    @Test
    void run_returns404WhenChildIsNotInInstitution() throws Exception {
        willThrow(new SummaryChildNotFoundException(8L)).given(summaryRunService).requestRun(any(), any(), any());

        mockMvc.perform(post(RUN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"childId\": 8, \"entryDate\": \"2026-10-08\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUMMARY_CHILD_NOT_FOUND"));
    }

    @Test
    void run_returns409WhileGroupIsBeingSummarized() throws Exception {
        willThrow(new SummaryException(SummaryErrorCode.IN_PROGRESS, "summarizing"))
                .given(summaryRunService).requestRun(any(), any(), any());

        mockMvc.perform(post(RUN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"childId\": 8, \"entryDate\": \"2026-10-08\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUMMARY_IN_PROGRESS"));
    }

    @Test
    void run_returns400WhenNothingToSummarize() throws Exception {
        willThrow(new SummaryRunValidationException("no validated entry"))
                .given(summaryRunService).requestRun(any(), any(), any());

        mockMvc.perform(post(RUN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"childId\": 8, \"entryDate\": \"2026-10-08\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUMMARY_INVALID_REQUEST"));
    }

    private static final String GATE1_URL = "/api/v1/summaries/50/gate1";

    private org.springframework.test.web.servlet.ResultActions decide(String body) throws Exception {
        return mockMvc.perform(post(GATE1_URL).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void gate1_passesDecisionAndCardsToService() throws Exception {
        List<Card> cards = List.of(new Card(0, "KEEP", null), new Card(1, "DELETE", null),
                new Card(2, "EDIT", "고친 문장"), new Card(null, "ADD", "새 문장"));
        given(summaryGate1Service.decide(any(), eq(50L), eq(Gate1Action.APPROVE), eq(cards), eq("v1")))
                .willReturn(new Gate1DecisionResponse(50L, "APPROVED", "CORRECTED"));

        decide("{\"decision\": \"approve\", \"version\": \"v1\", \"cards\": ["
                + "{\"index\": 0, \"action\": \"KEEP\"}, {\"index\": 1, \"action\": \"DELETE\"}, "
                + "{\"index\": 2, \"action\": \"EDIT\", \"text\": \"고친 문장\"}, "
                + "{\"action\": \"ADD\", \"text\": \"새 문장\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summaryId").value(50))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.decision").value("CORRECTED"));
    }

    @Test
    void gate1_acceptsHoldAndRemoveWithoutCards() throws Exception {
        decide("{\"decision\": \"hold\", \"version\": \"v1\"}").andExpect(status().isOk());
        decide("{\"decision\": \"remove\", \"version\": \"v1\"}").andExpect(status().isOk());

        verify(summaryGate1Service).decide(any(), eq(50L), eq(Gate1Action.HOLD), eq(null), eq("v1"));
        verify(summaryGate1Service).decide(any(), eq(50L), eq(Gate1Action.REMOVE), eq(null), eq("v1"));
    }

    @Test
    void gate1_acceptsConfirmWithoutCards() throws Exception {
        given(summaryGate1Service.decide(any(), eq(50L), eq(Gate1Action.CONFIRM), eq(null), eq("v1")))
                .willReturn(new Gate1DecisionResponse(50L, "CONFIRMED", "CONFIRMED"));

        decide("{\"decision\": \"confirm\", \"version\": \"v1\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.decision").value("CONFIRMED"));
    }

    @Test
    void gate1_acceptsRevertWithoutCards() throws Exception {
        given(summaryGate1Service.decide(any(), eq(50L), eq(Gate1Action.REVERT), eq(null), eq("v1")))
                .willReturn(new Gate1DecisionResponse(50L, "REVERTED", "REVERTED"));

        decide("{\"decision\": \"revert\", \"version\": \"v1\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVERTED"))
                .andExpect(jsonPath("$.data.decision").value("REVERTED"));
    }

    @Test
    void gate1_rejectsCardsOnConfirm() throws Exception {
        decide("{\"decision\": \"confirm\", \"version\": \"v1\", \"cards\": [{\"action\": \"ADD\", \"text\": \"새 문장\"}]}")
                .andExpect(status().isBadRequest());
        decide("{\"decision\": \"revert\", \"version\": \"v1\", \"cards\": [{\"action\": \"ADD\", \"text\": \"새 문장\"}]}")
                .andExpect(status().isBadRequest());

        verify(summaryGate1Service, never()).decide(any(), any(), any(), any(), any());
    }

    @Test
    void gate1_rejectsInvalidRequest() throws Exception {
        for (String body : new String[] {
                "{}",
                "{\"decision\": \"approve\"}",
                "{\"decision\": \"reject\", \"version\": \"v1\"}",
                "{\"decision\": \"APPROVE\", \"version\": \"v1\"}",
                "{\"decision\": \"approve\", \"version\": \"v1\", \"cards\": []}",
                "{\"decision\": \"approve\", \"editedContent\": \"고친 본문\", \"version\": \"v1\"}",
                "{\"decision\": \"hold\", \"editedContent\": \"고친 본문\", \"version\": \"v1\"}",
                approveWithCard("{\"action\": \"ADD\", \"text\": \"" + "가".repeat(1001) + "\"}"),
                "{\"decision\": \"hold\", \"version\": \"v1\", \"cards\": [{\"index\": 0, \"action\": \"KEEP\"}]}",
                approveWithCard("{\"index\": 0, \"action\": \"keep\"}"),
                approveWithCard("{\"index\": 0, \"action\": \"MOVE\"}"),
                approveWithCard("{\"index\": 0}"),
                approveWithCard("null"),
                approveWithCard("{\"action\": \"KEEP\"}"),
                approveWithCard("{\"index\": -1, \"action\": \"KEEP\"}"),
                approveWithCard("{\"index\": 0, \"action\": \"KEEP\", \"text\": \"글\"}"),
                approveWithCard("{\"index\": 0, \"action\": \"DELETE\", \"text\": \"글\"}"),
                approveWithCard("{\"index\": 0, \"action\": \"EDIT\"}"),
                approveWithCard("{\"index\": 0, \"action\": \"EDIT\", \"text\": \"  \"}"),
                approveWithCard("{\"action\": \"ADD\"}"),
                approveWithCard("{\"index\": 0, \"action\": \"ADD\", \"text\": \"새 문장\"}")}) {
            decide(body).andExpect(status().isBadRequest());
        }

        verify(summaryGate1Service, never()).decide(any(), any(), any(), any(), any());
    }

    private static String approveWithCard(String card) {
        return "{\"decision\": \"approve\", \"version\": \"v1\", \"cards\": [" + card + "]}";
    }

    @Test
    void gate1_returns404WhenSummaryIsNotInInstitution() throws Exception {
        given(summaryGate1Service.decide(any(), any(), any(), any(), any())).willThrow(new SummaryException(SummaryErrorCode.NOT_FOUND, 50L));

        decide("{\"decision\": \"approve\", \"version\": \"v1\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUMMARY_NOT_FOUND"));
    }

    @Test
    void gate1_returns409WhenAlreadyReviewedOrRegenerating() throws Exception {
        given(summaryGate1Service.decide(any(), any(), any(), any(), any()))
                .willThrow(new SummaryException(SummaryErrorCode.ALREADY_REVIEWED, 50L))
                .willThrow(new SummaryException(SummaryErrorCode.REGENERATING, 50L))
                .willThrow(new SummaryException(SummaryErrorCode.CHANGED, 50L))
                .willThrow(new SummaryException(SummaryErrorCode.HELD, 50L));

        for (String code : new String[] {"SUMMARY_ALREADY_REVIEWED", "SUMMARY_REGENERATING", "SUMMARY_CHANGED",
                "SUMMARY_HELD"}) {
            decide("{\"decision\": \"approve\", \"version\": \"v1\"}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(code));
        }
    }

    @Test
    void reopen_returnsReopenedSummary() throws Exception {
        given(summaryGate1Service.reopen(any(), eq(50L)))
                .willReturn(new Gate1DecisionResponse(50L, "GATE1_PENDING", "REOPENED"));

        mockMvc.perform(post("/api/v1/summaries/50/reopen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("GATE1_PENDING"))
                .andExpect(jsonPath("$.data.decision").value("REOPENED"));
    }

    @Test
    void reopen_returns409WhenSummaryIsNotHeld() throws Exception {
        given(summaryGate1Service.reopen(any(), any()))
                .willThrow(new SummaryException(SummaryErrorCode.NOT_HELD, 50L));

        mockMvc.perform(post("/api/v1/summaries/50/reopen"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUMMARY_NOT_HELD"));
    }

    @Test
    void list_returnsPendingSummariesByDefault() throws Exception {
        given(summaryGate1QueryService.getSummaries(any(), eq("pending"))).willReturn(List.of(
                new Gate1SummaryResponse(50L, 8L, "김준호", LocalDate.of(2026, 10, 8), 1, "GATE1_PENDING",
                        "v1", false, "요약 본문", List.of(new Gate1SummaryResponse.ClaimCard(0, "요약 본문",
                                List.of(new Gate1SummaryResponse.ClaimEvidence(1041L, "블록을 쌓았다")))),
                        false, true, List.of("다른아동이름"),
                        List.of(new Gate1SummaryResponse.UncoveredEntry(1042L, "낮잠을 잤다.")),
                        List.of(new Gate1SummaryResponse.ReviewFlag(1041L, List.of("추측성표현"),
                                List.of("관찰되지 않은 내용을 추측한 표현이 있어요"), List.of(0))),
                        List.of(new Gate1SummaryResponse.SourceEntry(1041L, "블록을 쌓았다."),
                                new Gate1SummaryResponse.SourceEntry(1042L, "낮잠을 잤다.")))));

        mockMvc.perform(get("/api/v1/summaries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(50))
                .andExpect(jsonPath("$.data[0].entryDate").value("2026-10-08"))
                .andExpect(jsonPath("$.data[0].version").value("v1"))
                .andExpect(jsonPath("$.data[0].processing").value(false))
                .andExpect(jsonPath("$.data[0].claims[0].index").value(0))
                .andExpect(jsonPath("$.data[0].claims[0].text").value("요약 본문"))
                .andExpect(jsonPath("$.data[0].claims[0].evidence[0].journalEntryId").value(1041))
                .andExpect(jsonPath("$.data[0].claims[0].evidence[0].quote").value("블록을 쌓았다"))
                .andExpect(jsonPath("$.data[0].noContent").value(false))
                .andExpect(jsonPath("$.data[0].reviewReasons[0]").value("다른아동이름"))
                .andExpect(jsonPath("$.data[0].uncoveredEntries[0].journalEntryId").value(1042))
                .andExpect(jsonPath("$.data[0].reviewFlags[0].issueTypes[0]").value("추측성표현"))
                .andExpect(jsonPath("$.data[0].reviewFlags[0].reasons[0]").value("관찰되지 않은 내용을 추측한 표현이 있어요"))
                .andExpect(jsonPath("$.data[0].reviewFlags[0].claimIndexes[0]").value(0))
                .andExpect(jsonPath("$.data[0].entries[0].journalEntryId").value(1041))
                .andExpect(jsonPath("$.data[0].entries[1].content").value("낮잠을 잤다."));
    }

    @Test
    void list_passesGate1StatusToService() throws Exception {
        mockMvc.perform(get("/api/v1/summaries").param("gate1Status", "hold")).andExpect(status().isOk());

        verify(summaryGate1QueryService).getSummaries(any(), eq("hold"));
    }

    @Test
    void list_returns400WithoutEchoingInput() throws Exception {
        given(summaryGate1QueryService.getSummaries(any(), any()))
                .willThrow(new SummaryException(SummaryErrorCode.INVALID_REQUEST, "unsupported gate1Status: <x>"));

        mockMvc.perform(get("/api/v1/summaries").param("gate1Status", "<x>"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUMMARY_INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(SummaryErrorCode.INVALID_REQUEST.getMessage()));
    }

    private static final String FAILED_BODY = "{\"childId\": 8, \"entryDate\": \"2026-10-08\"}";

    @Test
    void failed_returnsFailedGroups() throws Exception {
        given(summaryFailureService.getFailedGroups(any())).willReturn(List.of(new FailedSummaryGroupResponse(
                8L, "김하늘", LocalDate.of(2026, 10, 8),
                List.of(new FailedSummaryGroupResponse.Entry(31L, "블록 놀이를 했다.")), "NO_GROUNDED_CLAIMS")));

        mockMvc.perform(get("/api/v1/summaries/failed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].childId").value(8))
                .andExpect(jsonPath("$.data[0].childName").value("김하늘"))
                .andExpect(jsonPath("$.data[0].entryDate").value("2026-10-08"))
                .andExpect(jsonPath("$.data[0].entries[0].journalEntryId").value(31))
                .andExpect(jsonPath("$.data[0].entries[0].content").value("블록 놀이를 했다."))
                .andExpect(jsonPath("$.data[0].reason").value("NO_GROUNDED_CLAIMS"));
    }

    @Test
    void failedRetry_sendsGroupBackToSummary() throws Exception {
        mockMvc.perform(post("/api/v1/summaries/failed/retry")
                        .contentType(MediaType.APPLICATION_JSON).content(FAILED_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"));

        verify(summaryFailureService).retry(any(), eq(8L), eq(LocalDate.of(2026, 10, 8)));
    }

    @Test
    void failedClose_closesGroupWithoutSummary() throws Exception {
        mockMvc.perform(post("/api/v1/summaries/failed/close")
                        .contentType(MediaType.APPLICATION_JSON).content(FAILED_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"));

        verify(summaryFailureService).close(any(), eq(8L), eq(LocalDate.of(2026, 10, 8)));
    }

    @Test
    void failedRetryAndClose_rejectInvalidBody() throws Exception {
        for (String url : new String[] {"/api/v1/summaries/failed/retry", "/api/v1/summaries/failed/close"}) {
            for (String body : new String[] {
                    "{\"childId\": 8}",
                    "{\"childId\": 8, \"entryDate\": \"2026-02-30\"}",
                    "{\"entryDate\": \"2026-10-08\"}"}) {
                mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isBadRequest());
            }
        }

        verify(summaryFailureService, never()).retry(any(), any(), any());
        verify(summaryFailureService, never()).close(any(), any(), any());
    }

    @Test
    void failedRetry_returns404WhenNothingLeftOrChildNotInInstitution() throws Exception {
        willThrow(new SummaryException(SummaryErrorCode.FAILED_NOT_FOUND, "none"))
                .given(summaryFailureService).retry(any(), any(), any());
        willThrow(new SummaryChildNotFoundException(8L))
                .given(summaryFailureService).close(any(), any(), any());

        mockMvc.perform(post("/api/v1/summaries/failed/retry")
                        .contentType(MediaType.APPLICATION_JSON).content(FAILED_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUMMARY_FAILED_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/summaries/failed/close")
                        .contentType(MediaType.APPLICATION_JSON).content(FAILED_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUMMARY_CHILD_NOT_FOUND"));
    }
}
