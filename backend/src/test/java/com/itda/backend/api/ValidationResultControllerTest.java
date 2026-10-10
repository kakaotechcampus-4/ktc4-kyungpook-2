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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.itda.backend.dto.RecordResponse;
import com.itda.backend.dto.ValidationQueueItemResponse;
import com.itda.backend.exception.ValidationResultNotFoundException;
import com.itda.backend.exception.ValidationResultValidationException;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.service.ValidationQueueService;

@WebMvcTest(ValidationResultController.class)
@AutoConfigureMockMvc(addFilters = false)
class ValidationResultControllerTest {

    private static final String BASE_URL = "/api/v1/validation-results";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ValidationQueueService validationQueueService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private JwtCookie jwtCookie;

    @Test
    void getQueue_returnsBlockedItems() throws Exception {
        var item = new ValidationQueueItemResponse(
                "11", 1L,
                RecordResponse.of("5", "0821_특이사항.txt", "오늘 있었던 일", "2026-08-21"),
                "김하늘",
                "전화번호·주민번호 같은 개인정보가 그대로 적혀 있습니다",
                List.of("개인정보표현"));
        given(validationQueueService.getBlockedQueue(any())).willReturn(List.of(item));

        mockMvc.perform(get(BASE_URL).param("status", "BLOCK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].childName").value("김하늘"))
                .andExpect(jsonPath("$.data[0].violationReason")
                        .value("전화번호·주민번호 같은 개인정보가 그대로 적혀 있습니다"))
                .andExpect(jsonPath("$.data[0].violationCode[0]").value("개인정보표현"))
                .andExpect(jsonPath("$.data[0].record.fileName").value("0821_특이사항.txt"));
    }

    /** FE는 status 없이 부르지 않지만, 생략돼도 BLOCK 큐를 돌려준다. */
    @Test
    void getQueue_defaultsToBlockWhenStatusOmitted() throws Exception {
        given(validationQueueService.getBlockedQueue(any())).willReturn(List.of());

        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk());

        verify(validationQueueService).getBlockedQueue(any());
    }

    /** 다른 status를 조용히 BLOCK으로 처리하면 화면이 엉뚱한 목록을 보여준다 — 명시적으로 거절한다. */
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
                        .content("{\"action\":\"reupload\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"));

        verify(validationQueueService).resolve(eq(11L), eq("reupload"), any());
    }

    @Test
    void resolve_unsupportedAction_returns400() throws Exception {
        willThrow(new ValidationResultValidationException("unsupported action: approve"))
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
}
