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

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.itda.backend.domain.approval.Gate2Action;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.dto.response.insight.Gate2DecisionResponse;
import com.itda.backend.dto.response.insight.Gate2InsightResponse;
import com.itda.backend.dto.response.insight.Gate2InsightResponse.EvidenceRef;
import com.itda.backend.dto.response.insight.Gate2InsightResponse.Target;
import com.itda.backend.dto.response.organization.InstitutionResponse;
import com.itda.backend.exception.insight.InsightErrorCode;
import com.itda.backend.exception.insight.InsightException;
import com.itda.backend.exception.insight.InsightExceptionHandler;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.service.insight.InsightGate2QueryService;
import com.itda.backend.service.insight.InsightGate2Service;

@WebMvcTest(InsightController.class)
@Import(InsightExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class InsightControllerTest {

    private static final String LIST_URL = "/api/v1/insights";
    private static final String GATE2_URL = "/api/v1/insights/1/gate2";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InsightGate2QueryService insightGate2QueryService;

    @MockitoBean
    private InsightGate2Service insightGate2Service;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private JwtCookie jwtCookie;

    @Test
    void list_defaultsToPendingAndReturnsScreenShape() throws Exception {
        given(insightGate2QueryService.getInsights(any(), eq("pending"))).willReturn(List.of(insight()));

        mockMvc.perform(get(LIST_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("1"))
                .andExpect(jsonPath("$.data[0].period").value("2026년 8월 3주차"))
                .andExpect(jsonPath("$.data[0].needsReview").value(true))
                .andExpect(jsonPath("$.data[0].primarySource.type").value("school"))
                .andExpect(jsonPath("$.data[0].primarySource.verified").value(false))
                .andExpect(jsonPath("$.data[0].evidence[0].childContextId").value("10"))
                .andExpect(jsonPath("$.data[0].targets[0].consent").value("granted"))
                .andExpect(jsonPath("$.data[0].gate2Status").value("pending"));
    }

    @Test
    void list_passesRequestedStatus() throws Exception {
        given(insightGate2QueryService.getInsights(any(), eq("approved"))).willReturn(List.of());

        mockMvc.perform(get(LIST_URL).param("gate2Status", "approved")).andExpect(status().isOk());

        verify(insightGate2QueryService).getInsights(any(), eq("approved"));
    }

    @Test
    void gate2_passesDecisionToService() throws Exception {
        given(insightGate2Service.decide(any(), eq(1L), eq(Gate2Action.APPROVE)))
                .willReturn(new Gate2DecisionResponse("1", "approved", "APPROVED"));

        mockMvc.perform(post(GATE2_URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\": \"approve\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.gate2Status").value("approved"))
                .andExpect(jsonPath("$.data.decision").value("APPROVED"));
    }

    /** 기관이 수신처를 고르지 않으므로 대상 기관은 받지 않는다. 허용되지 않은 결정값도 막는다. */
    @Test
    void gate2_rejectsMissingOrUnknownDecision() throws Exception {
        for (String body : new String[] {"{}", "{\"decision\": \"remove\"}", "{\"decision\": \"\"}"}) {
            mockMvc.perform(post(GATE2_URL).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }

        verify(insightGate2Service, never()).decide(any(), any(), any());
    }

    @Test
    void gate2_mapsInsightErrorsToStatusCodes() throws Exception {
        assertDecideFails(InsightErrorCode.NOT_FOUND, 404, "INSIGHT_NOT_FOUND");
        assertDecideFails(InsightErrorCode.NOT_PRIMARY_SOURCE, 403, "INSIGHT_NOT_PRIMARY_SOURCE");
        assertDecideFails(InsightErrorCode.ALREADY_REVIEWED, 409, "INSIGHT_ALREADY_REVIEWED");
        assertDecideFails(InsightErrorCode.SUPERSEDED, 409, "INSIGHT_SUPERSEDED");
    }

    private void assertDecideFails(InsightErrorCode errorCode, int status, String code) throws Exception {
        willThrow(new InsightException(errorCode, 1L)).given(insightGate2Service).decide(any(), any(), any());

        mockMvc.perform(post(GATE2_URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\": \"approve\"}"))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code));
    }

    private static Gate2InsightResponse insight() {
        InstitutionResponse school = new InstitutionResponse("1", "○○초등학교", "school", false);
        InstitutionResponse center = new InstitutionResponse("2", "햇살센터", "center", false);
        return new Gate2InsightResponse("1", "1", "김하늘", "2026년 8월 3주차", "오후 시간대 …", true, school,
                List.of(new EvidenceRef("10", "2026-08-19", "관찰일지 요약", center)),
                List.of(new Target(center, ConsentState.GRANTED)), "pending");
    }
}
