package com.itda.backend.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.itda.backend.dto.response.agent.AiStatusResponse;
import com.itda.backend.exception.user.UserErrorCode;
import com.itda.backend.exception.user.UserException;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.service.agent.AiStatusService;

@WebMvcTest(SystemController.class)
@AutoConfigureMockMvc(addFilters = false)
class SystemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AiStatusService aiStatusService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private JwtCookie jwtCookie;

    @Test
    void AI가_멈췄으면_멈춘_시각과_함께_알려준다() throws Exception {
        given(aiStatusService.getStatus(any())).willReturn(new AiStatusResponse(false,
                OffsetDateTime.of(2026, 11, 6, 10, 0, 0, 0, ZoneOffset.ofHours(9))));

        mockMvc.perform(get("/api/v1/system/ai-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.since").value("2026-11-06T10:00:00+09:00"));
    }

    @Test
    void AI가_살아_있으면_시각은_null_이다() throws Exception {
        given(aiStatusService.getStatus(any())).willReturn(new AiStatusResponse(true, null));

        mockMvc.perform(get("/api/v1/system/ai-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.since").doesNotExist());
    }

    @Test
    void 기관_사용자가_아니면_403() throws Exception {
        given(aiStatusService.getStatus(any()))
                .willThrow(new UserException(UserErrorCode.ORGANIZATION_NOT_ASSIGNED));

        mockMvc.perform(get("/api/v1/system/ai-status"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_ASSIGNED"));
    }
}
