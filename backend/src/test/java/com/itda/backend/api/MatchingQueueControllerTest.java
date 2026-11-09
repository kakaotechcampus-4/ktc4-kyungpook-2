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
import com.itda.backend.domain.matching.MatchingStatus;
import com.itda.backend.dto.response.matching.MatchingQueueItemResponse;
import com.itda.backend.dto.response.record.RecordResponse;
import com.itda.backend.exception.matching.MatchingResultNotFoundException;
import com.itda.backend.exception.matching.MatchingResultValidationException;
import com.itda.backend.service.matching.MatchingResultService;

@WebMvcTest(MatchingQueueController.class)
@AutoConfigureMockMvc(addFilters = false)
class MatchingQueueControllerTest {

    private static final String BASE_URL = "/api/v1/matching-queue";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MatchingResultService matchingResultService;

    @Test
    void getQueue_excludesAutoStatus() throws Exception {
        var review = new MatchingQueueItemResponse(
                "1", 1L, null, MatchingStatus.REVIEW, new BigDecimal("0.4"), null, null, "김하늘",
                new RecordResponse("3", "0821_관찰일지.docx", "점심시간에…", "2026-08-21", "점심시간에 식사를 잘함"),
                List.of(),
                MAPPER.readTree("[{\"start\":0,\"end\":3}]"));
        given(matchingResultService.getQueue(any())).willReturn(List.of(review));

        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].status").value("review"))
                .andExpect(jsonPath("$.data[0].record.fileName").value("0821_관찰일지.docx"))
                .andExpect(jsonPath("$.data[0].hintName").value("김하늘"))
                .andExpect(jsonPath("$.data[0].evidence[0].start").value(0))
                .andExpect(jsonPath("$.data[0].evidence[0].end").value(3));
    }

    @Test
    void getItem_returnsOneItem() throws Exception {
        var item = new MatchingQueueItemResponse(
                "1", 1L, null, MatchingStatus.REVIEW, new BigDecimal("0.4"), null, null, null,
                new RecordResponse("3", "0821_관찰일지.docx", "점심시간에…", "2026-08-21", "점심시간에 식사를 잘함"),
                List.of(), null);
        given(matchingResultService.getItem(eq(1L), any())).willReturn(item);

        mockMvc.perform(get(BASE_URL + "/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("1"))
                .andExpect(jsonPath("$.data.record.fullContent").value("점심시간에 식사를 잘함"));
    }

    @Test
    void getItemNotOurs_returns404() throws Exception {
        given(matchingResultService.getItem(eq(999L), any())).willThrow(new MatchingResultNotFoundException(999L));

        mockMvc.perform(get(BASE_URL + "/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MATCHING_RESULT_NOT_FOUND"));
    }

    @Test
    void resolveAssign_updatesMatchedChild() throws Exception {
        var resolved = new MatchingQueueItemResponse(
                "1", 1L, "2", MatchingStatus.AUTO, new BigDecimal("0.4"), null, null, null,
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

    @Test
    void resolveAssignUnavailableChild_returns400WithFixedMessage() throws Exception {
        given(matchingResultService.resolve(eq(1L), eq("assign"), eq(2L), any(), any()))
                .willThrow(new MatchingResultValidationException("child not available: 2"));

        mockMvc.perform(post(BASE_URL + "/1/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"assign\",\"childId\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MATCHING_RESULT_INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("요청이 올바르지 않습니다."));
    }
}
