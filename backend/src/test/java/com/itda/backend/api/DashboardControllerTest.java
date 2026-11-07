package com.itda.backend.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.itda.backend.dto.response.dashboard.AiActivityResponse;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse.Detail;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse.Kind;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse.Row;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse.Status;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse.Target;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse.Turn;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse.Week;
import com.itda.backend.exception.user.UserErrorCode;
import com.itda.backend.exception.user.UserException;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.service.dashboard.DashboardActivityService;
import com.itda.backend.service.dashboard.DashboardPipelineService;

@WebMvcTest(DashboardController.class)
@AutoConfigureMockMvc(addFilters = false)
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardActivityService dashboardActivityService;

    @MockitoBean
    private DashboardPipelineService dashboardPipelineService;

    @Test
    void 선생님_차례와_진행_현황_줄을_소문자_값으로_보여준다() throws Exception {
        given(dashboardPipelineService.getPipeline(any())).willReturn(new DashboardPipelineResponse(
                new Turn(1, 2, 3, 4, 5),
                List.of(
                        new Row(Kind.ENTRY, "8", "김준호", LocalDate.of(2026, 11, 5), null, 1,
                                Status.BLOCKED.who(), Status.BLOCKED,
                                new Detail(null, List.of("진단명"), List.of("진단명이 있어요"), null, null),
                                new Target("validation", "30")),
                        new Row(Kind.WEEK, "9", "이서아", null,
                                new Week(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1), "2026년 10월 4주차"),
                                5, Status.REVIEW_ELSEWHERE.who(), Status.REVIEW_ELSEWHERE,
                                new Detail(null, null, null, null, "해솔초등학교"), null))));

        mockMvc.perform(get("/api/v1/dashboard/pipeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.turn.confirm").value(1))
                .andExpect(jsonPath("$.data.turn.requests").value(5))
                .andExpect(jsonPath("$.data.rows[0].kind").value("entry"))
                .andExpect(jsonPath("$.data.rows[0].childId").value("8"))
                .andExpect(jsonPath("$.data.rows[0].date").value("2026-11-05"))
                .andExpect(jsonPath("$.data.rows[0].step").value(1))
                .andExpect(jsonPath("$.data.rows[0].who").value("me"))
                .andExpect(jsonPath("$.data.rows[0].status").value("blocked"))
                .andExpect(jsonPath("$.data.rows[0].detail.kinds[0]").value("진단명"))
                .andExpect(jsonPath("$.data.rows[0].detail.approverName").doesNotExist())
                .andExpect(jsonPath("$.data.rows[0].target.type").value("validation"))
                .andExpect(jsonPath("$.data.rows[0].target.id").value("30"))
                .andExpect(jsonPath("$.data.rows[1].kind").value("week"))
                .andExpect(jsonPath("$.data.rows[1].week.from").value("2026-10-26"))
                .andExpect(jsonPath("$.data.rows[1].week.label").value("2026년 10월 4주차"))
                .andExpect(jsonPath("$.data.rows[1].who").value("other"))
                .andExpect(jsonPath("$.data.rows[1].status").value("review_elsewhere"))
                .andExpect(jsonPath("$.data.rows[1].detail.approverName").value("해솔초등학교"));
    }

    @Test
    void 기관_사용자가_아니면_진행_현황을_볼_수_없다() throws Exception {
        given(dashboardPipelineService.getPipeline(any()))
                .willThrow(new UserException(UserErrorCode.ORGANIZATION_NOT_ASSIGNED));

        mockMvc.perform(get("/api/v1/dashboard/pipeline"))
                .andExpect(status().isForbidden());
    }

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
