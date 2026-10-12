package com.itda.backend.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.itda.backend.exception.SummaryChildNotFoundException;
import com.itda.backend.exception.SummaryRunValidationException;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
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
}
