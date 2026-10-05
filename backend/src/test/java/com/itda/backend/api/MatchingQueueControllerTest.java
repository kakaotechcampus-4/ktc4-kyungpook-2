package com.itda.backend.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.domain.MatchingStatus;
import com.itda.backend.dto.MatchingQueueItemResponse;
import com.itda.backend.dto.RecordResponse;
import com.itda.backend.exception.MatchingResultNotFoundException;
import com.itda.backend.exception.MatchingResultValidationException;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.service.MatchingResultService;

@WebMvcTest(MatchingQueueController.class)
@AutoConfigureMockMvc(addFilters = false)
class MatchingQueueControllerTest {

    private static final String BASE_URL = "/api/v1/matching-queue";
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        var review = new MatchingQueueItemResponse(
                "1", 1L, null, MatchingStatus.REVIEW, new BigDecimal("0.4"), null, null,
                new RecordResponse("3", "0821_관찰일지.docx", "점심시간에…", "2026-08-21", "점심시간에 식사를 잘함"),
                List.of(),
                MAPPER.readTree("[{\"start\":0,\"end\":3}]"));
        given(matchingResultService.getQueue(any())).willReturn(List.of(review));

        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].status").value("review"))
                .andExpect(jsonPath("$.data[0].record.fileName").value("0821_관찰일지.docx"))
                // 팀원 리뷰 반영: evidence는 문자열이 아니라 실제 JSON 배열로 내려가야
                // 프론트(EvidenceSpan[])가 바로 쓸 수 있다.
                .andExpect(jsonPath("$.data[0].evidence[0].start").value(0))
                .andExpect(jsonPath("$.data[0].evidence[0].end").value(3));
    }

    @Test
    void resolveAssign_updatesMatchedChild() throws Exception {
        var resolved = new MatchingQueueItemResponse(
                "1", 1L, "2", MatchingStatus.AUTO, new BigDecimal("0.4"), null, null,
                new RecordResponse("3", "0821_관찰일지.docx", "점심시간에…", "2026-08-21", "점심시간에 식사를 잘함"),
                List.of(), null);
        given(matchingResultService.resolve(eq(1L), eq("assign"), eq(2L), any(), any())).willReturn(resolved);

        mockMvc.perform(post(BASE_URL + "/1/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"assign\",\"childId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matchedChildId").value("2"))
                .andExpect(jsonPath("$.data.status").value("auto"));
    }

    @Test
    void resolveUnknownId_returns404() throws Exception {
        given(matchingResultService.resolve(eq(999L), eq("assign"), eq(2L), any(), any()))
                .willThrow(new MatchingResultNotFoundException(999L));

        mockMvc.perform(post(BASE_URL + "/999/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"assign\",\"childId\":2}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MATCHING_RESULT_NOT_FOUND"));
    }

    @Test
    void resolveMissingChildIdForAssign_returns400() throws Exception {
        given(matchingResultService.resolve(eq(1L), eq("assign"), isNull(), any(), any()))
                .willThrow(new MatchingResultValidationException("childId is required for assign"));

        mockMvc.perform(post(BASE_URL + "/1/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"assign\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MATCHING_RESULT_INVALID_REQUEST"));
    }
}
