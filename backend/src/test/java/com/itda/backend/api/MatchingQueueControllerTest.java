package com.itda.backend.api;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.itda.backend.domain.MatchingResult;
import com.itda.backend.domain.MatchingStatus;
import com.itda.backend.exception.MatchingResultNotFoundException;
import com.itda.backend.exception.MatchingResultValidationException;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.service.MatchingResultService;

@WebMvcTest(MatchingQueueController.class)
@AutoConfigureMockMvc(addFilters = false)
class MatchingQueueControllerTest {

    private static final String BASE_URL = "/api/v1/matching-queue";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MatchingResultService matchingResultService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private JwtCookie jwtCookie;

    @Test
    void getQueue_excludesAutoStatus() throws Exception {
        MatchingResult review = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW,
                null, "[{\"start\":0,\"end\":3}]", null, "v1");
        given(matchingResultService.getQueue()).willReturn(List.of(review));

        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].status").value("review"))
                .andExpect(jsonPath("$.data[0].evidence").value("[{\"start\":0,\"end\":3}]"));
    }

    @Test
    void resolveAssign_updatesMatchedChild() throws Exception {
        MatchingResult resolved = new MatchingResult(
                1L, 2L, new BigDecimal("0.4"), MatchingStatus.AUTO, null, null, null, "v1");
        given(matchingResultService.resolve(1L, "assign", 2L)).willReturn(resolved);

        mockMvc.perform(post(BASE_URL + "/1/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"assign\",\"childId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matchedChildId").value("2"))
                .andExpect(jsonPath("$.data.status").value("auto"));
    }

    @Test
    void resolveUnknownId_returns404() throws Exception {
        given(matchingResultService.resolve(999L, "assign", 2L))
                .willThrow(new MatchingResultNotFoundException(999L));

        mockMvc.perform(post(BASE_URL + "/999/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"assign\",\"childId\":2}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MATCHING_RESULT_NOT_FOUND"));
    }

    @Test
    void resolveMissingChildIdForAssign_returns400() throws Exception {
        given(matchingResultService.resolve(1L, "assign", null))
                .willThrow(new MatchingResultValidationException("childId is required for assign"));

        mockMvc.perform(post(BASE_URL + "/1/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"assign\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MATCHING_RESULT_INVALID_REQUEST"));
    }
}
