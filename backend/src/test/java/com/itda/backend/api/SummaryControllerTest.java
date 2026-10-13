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

import com.itda.backend.dto.response.summary.Gate1DecisionResponse;
import com.itda.backend.dto.response.summary.Gate1SummaryResponse;
import com.itda.backend.exception.summary.SummaryChildNotFoundException;
import com.itda.backend.exception.summary.SummaryException;
import com.itda.backend.exception.summary.SummaryErrorCode;
import com.itda.backend.exception.summary.SummaryRunValidationException;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.domain.approval.Gate1Action;
import com.itda.backend.service.approval.SummaryGate1QueryService;
import com.itda.backend.service.approval.SummaryGate1Service;
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
    private JwtProvider jwtProvider;

    @MockitoBean
    private JwtCookie jwtCookie;

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
    void gate1_passesDecisionAndEditedContentToService() throws Exception {
        given(summaryGate1Service.decide(any(), eq(50L), eq(Gate1Action.APPROVE), eq("고친 본문")))
                .willReturn(new Gate1DecisionResponse(50L, "APPROVED", "CORRECTED"));

        decide("{\"decision\": \"approve\", \"editedContent\": \"고친 본문\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summaryId").value(50))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.decision").value("CORRECTED"));
    }

    @Test
    void gate1_acceptsHoldAndRemoveWithoutEditedContent() throws Exception {
        decide("{\"decision\": \"hold\"}").andExpect(status().isOk());
        decide("{\"decision\": \"remove\"}").andExpect(status().isOk());

        verify(summaryGate1Service).decide(any(), eq(50L), eq(Gate1Action.HOLD), eq(null));
        verify(summaryGate1Service).decide(any(), eq(50L), eq(Gate1Action.REMOVE), eq(null));
    }

    @Test
    void gate1_rejectsInvalidRequest() throws Exception {
        for (String body : new String[] {
                "{}",
                "{\"decision\": \"reject\"}",
                "{\"decision\": \"APPROVE\"}",
                "{\"decision\": \"approve\", \"editedContent\": \"  \"}",
                // 보류·제거에 고친 본문을 보내면 버려진다. 조용히 무시하지 않고 막는다.
                "{\"decision\": \"hold\", \"editedContent\": \"고친 본문\"}"}) {
            decide(body).andExpect(status().isBadRequest());
        }

        verify(summaryGate1Service, never()).decide(any(), any(), any(), any());
    }

    @Test
    void gate1_returns404WhenSummaryIsNotInInstitution() throws Exception {
        given(summaryGate1Service.decide(any(), any(), any(), any())).willThrow(new SummaryException(SummaryErrorCode.NOT_FOUND, 50L));

        decide("{\"decision\": \"approve\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUMMARY_NOT_FOUND"));
    }

    @Test
    void gate1_returns409WhenAlreadyReviewedOrRegenerating() throws Exception {
        given(summaryGate1Service.decide(any(), any(), any(), any()))
                .willThrow(new SummaryException(SummaryErrorCode.ALREADY_REVIEWED, 50L))
                .willThrow(new SummaryException(SummaryErrorCode.REGENERATING, 50L));

        decide("{\"decision\": \"approve\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUMMARY_ALREADY_REVIEWED"));
        decide("{\"decision\": \"approve\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUMMARY_REGENERATING"));
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
                        "요약 본문", true, List.of("다른아동이름"),
                        List.of(new Gate1SummaryResponse.UncoveredEntry(1042L, "낮잠을 잤다.")),
                        List.of(new Gate1SummaryResponse.ReviewFlag(1041L, List.of("추측성표현"))))));

        mockMvc.perform(get("/api/v1/summaries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(50))
                .andExpect(jsonPath("$.data[0].entryDate").value("2026-10-08"))
                .andExpect(jsonPath("$.data[0].reviewReasons[0]").value("다른아동이름"))
                .andExpect(jsonPath("$.data[0].uncoveredEntries[0].journalEntryId").value(1042))
                .andExpect(jsonPath("$.data[0].reviewFlags[0].issueTypes[0]").value("추측성표현"));
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
}

