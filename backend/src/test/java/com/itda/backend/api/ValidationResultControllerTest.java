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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.itda.backend.dto.response.record.RecordResponse;
import com.itda.backend.dto.response.validation.ValidationQueueItemResponse;
import com.itda.backend.exception.validation.ValidationResultErrorCode;
import com.itda.backend.exception.validation.ValidationResultNotFoundException;
import com.itda.backend.exception.validation.ValidationResultValidationException;
import com.itda.backend.service.validation.ValidationQueueService;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.exception.agent.AgentQueueFullException;

@WebMvcTest(ValidationResultController.class)
@AutoConfigureMockMvc(addFilters = false)
class ValidationResultControllerTest {

    private static final String BASE_URL = "/api/v1/validation-results";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ValidationQueueService validationQueueService;

    @Test
    void getQueue_returnsBlockedItems() throws Exception {
        var item = new ValidationQueueItemResponse(
                "11", 1L,
                RecordResponse.of("5", "0821_특이사항.txt", "오늘 있었던 일", "2026-08-21"),
                "김하늘",
                "전화번호·주민번호 같은 개인정보가 그대로 적혀 있어요",
                List.of("개인정보표현"));
        given(validationQueueService.getBlockedQueue(any())).willReturn(List.of(item));

        mockMvc.perform(get(BASE_URL).param("status", "BLOCK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].childName").value("김하늘"))
                .andExpect(jsonPath("$.data[0].violationReason")
                        .value("전화번호·주민번호 같은 개인정보가 그대로 적혀 있어요"))
                .andExpect(jsonPath("$.data[0].violationCode[0]").value("개인정보표현"))
                .andExpect(jsonPath("$.data[0].record.fileName").value("0821_특이사항.txt"));
    }

    @Test
    void getQueue_defaultsToBlockWhenStatusOmitted() throws Exception {
        given(validationQueueService.getBlockedQueue(any())).willReturn(List.of());

        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk());

        verify(validationQueueService).getBlockedQueue(any());
    }

    @Test
    void getQueue_rejectsUnsupportedStatus() throws Exception {
        mockMvc.perform(get(BASE_URL).param("status", "REVIEW"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_RESULT_INVALID_REQUEST"));

        verify(validationQueueService, never()).getBlockedQueue(any());
    }

    @Test
    void resolve_passesActionToService() throws Exception {
        mockMvc.perform(post(BASE_URL + "/11/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"hold\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"));

        verify(validationQueueService).resolve(eq(11L), eq("hold"), any());
    }

    @Test
    void resolve_unsupportedAction_returns400() throws Exception {
        willThrow(new ValidationResultValidationException("unsupported action for validation result: 1"))
                .given(validationQueueService).resolve(any(), any(), any());

        mockMvc.perform(post(BASE_URL + "/11/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"approve\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_RESULT_INVALID_REQUEST"));
    }

    @Test
    void resolve_unknownOrOtherInstitution_returns404() throws Exception {
        willThrow(new ValidationResultNotFoundException(99L))
                .given(validationQueueService).resolve(any(), any(), any());

        mockMvc.perform(post(BASE_URL + "/99/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"hold\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VALIDATION_RESULT_NOT_FOUND"));
    }

    @Test
    void revise_passesContentToService() throws Exception {
        mockMvc.perform(post(BASE_URL + "/11/revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"점심을 잘 먹음\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"));

        verify(validationQueueService).revise(eq(11L), eq("점심을 잘 먹음"), any());
    }

    @Test
    void revise_blankContent_returns400WithoutCallingService() throws Exception {
        mockMvc.perform(post(BASE_URL + "/11/revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verify(validationQueueService, never()).revise(any(), any(), any());
    }

    @Test
    void revise_tooLongContent_returns400WithoutCallingService() throws Exception {
        String tooLong = "가".repeat(JournalEntry.MAX_CONTENT_LENGTH + 1);

        mockMvc.perform(post(BASE_URL + "/11/revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());

        verify(validationQueueService, never()).revise(any(), any(), any());
    }

    @Test
    void revise_contentAtMaxLength_callsService() throws Exception {
        mockMvc.perform(post(BASE_URL + "/11/revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + "가".repeat(JournalEntry.MAX_CONTENT_LENGTH) + "\"}"))
                .andExpect(status().isOk());

        verify(validationQueueService).revise(eq(11L), any(), any());
    }

    @Test
    void revise_agentQueueFull_returns429() throws Exception {
        willThrow(new AgentQueueFullException(1L, 1000, 1, 1000))
                .given(validationQueueService).revise(any(), any(), any());

        mockMvc.perform(post(BASE_URL + "/11/revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"고친 글\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AGENT_QUEUE_FULL"));
    }

    @ParameterizedTest
    @EnumSource(value = ValidationResultErrorCode.class, names = {"CONTENT_UNCHANGED", "OUTDATED", "CHILD_NOT_SHARED"})
    void revise_rejectedForReason_returns400WithItsCode(ValidationResultErrorCode code) throws Exception {
        willThrow(new ValidationResultValidationException(code, "rejected"))
                .given(validationQueueService).revise(any(), any(), any());

        mockMvc.perform(post(BASE_URL + "/11/revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"그대로\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(code.getCode()))
                .andExpect(jsonPath("$.message").value(code.getMessage()));
    }

    @Test
    void revise_unknownOrOtherInstitution_returns404() throws Exception {
        willThrow(new ValidationResultNotFoundException(99L))
                .given(validationQueueService).revise(any(), any(), any());

        mockMvc.perform(post(BASE_URL + "/99/revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"고친 글\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VALIDATION_RESULT_NOT_FOUND"));
    }
}
