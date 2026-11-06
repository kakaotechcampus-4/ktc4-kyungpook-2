package com.itda.backend.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.itda.backend.dto.response.dashboard.AiActivityResponse;
import com.itda.backend.exception.user.UserErrorCode;
import com.itda.backend.exception.user.UserException;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.service.dashboard.DashboardActivityService;

@WebMvcTest(DashboardController.class)
@AutoConfigureMockMvc(addFilters = false)
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardActivityService dashboardActivityService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private JwtCookie jwtCookie;

    @Test
    void 오늘_AI가_한_일을_단계마다_보여준다() throws Exception {
        given(dashboardActivityService.getActivity(any())).willReturn(new AiActivityResponse(12, 7, 11, 3, 1));

        mockMvc.perform(get("/api/v1/dashboard/ai-activity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.split").value(12))
                .andExpect(jsonPath("$.data.matched").value(7))
                .andExpect(jsonPath("$.data.validated").value(11))
                .andExpect(jsonPath("$.data.summarized").value(3))
                .andExpect(jsonPath("$.data.insights").value(1));
    }

    @Test
    void 기관_사용자가_아니면_오늘_AI가_한_일을_볼_수_없다() throws Exception {
        given(dashboardActivityService.getActivity(any()))
                .willThrow(new UserException(UserErrorCode.ORGANIZATION_NOT_ASSIGNED));

        mockMvc.perform(get("/api/v1/dashboard/ai-activity"))
                .andExpect(status().isForbidden());
    }
}
