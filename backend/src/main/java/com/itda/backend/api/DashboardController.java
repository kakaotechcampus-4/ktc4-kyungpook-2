package com.itda.backend.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.itda.backend.dto.response.dashboard.AiActivityResponse;
import com.itda.backend.dto.response.dashboard.DashboardAiProgressResponse;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse;
import com.itda.backend.global.config.OpenApiConfig;
import com.itda.backend.global.exception.ErrorResponse;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.dashboard.DashboardActivityService;
import com.itda.backend.service.dashboard.DashboardAiProgressService;
import com.itda.backend.service.dashboard.DashboardPipelineService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

// docs/api/api-spec.md O-80, O-81, O-83.
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
@Tag(name = "대시보드", description = "기관 첫 화면의 진행 현황·AI 진행 단계·오늘 AI가 한 일")
public class DashboardController {

    private final DashboardActivityService dashboardActivityService;
    private final DashboardPipelineService dashboardPipelineService;
    private final DashboardAiProgressService dashboardAiProgressService;

    @GetMapping("/pipeline")
    @Operation(
            summary = "선생님 차례와 진행 현황",
            description = "선생님 차례 5줄의 건수(turn)와 진행 현황 줄(rows)을 한 번에 돌려줍니다. 검토 승인 전에는 "
                    + "아이×날짜, 승인 뒤에는 아이×주 한 줄이고 단계(step 0~5)·차례(who)·상태(status)는 서버가 정합니다. "
                    + "명부에 있고 우리 기관 동의가 켜진 아이만 보입니다(아이 확인·막힌 기록은 그 큐와 같은 기준)."
    )
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "기관 사용자가 아님 (ORGANIZATION_NOT_ASSIGNED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<DashboardPipelineResponse> getPipeline(@AuthenticationPrincipal String userId) {
        return ApiResponse.success(dashboardPipelineService.getPipeline(userId));
    }

    @GetMapping("/ai-activity")
    @Operation(
            summary = "오늘 AI가 한 일",
            description = "서울 기준 오늘 0시부터 우리 기관 기록에 AI 가 한 일을 단계마다 셉니다. "
                    + "summarized 는 근사입니다(다시 요약한 검토 대기 판 포함)."
    )
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "기관 사용자가 아님 (ORGANIZATION_NOT_ASSIGNED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<AiActivityResponse> getAiActivity(@AuthenticationPrincipal String userId) {
        return ApiResponse.success(dashboardActivityService.getActivity(userId));
    }

    @GetMapping("/ai-progress")
    @Operation(
            summary = "진행 현황의 AI 진행 단계",
            description = "AI 가 지금 처리 중인 진행 현황 줄과 그 단계(node)·걸린 초를 돌려줍니다. kind·childId·date/weekFrom 은 "
                    + "진행 현황(/dashboard/pipeline) 줄과 같은 값입니다. 서버가 1초마다 AI 에 물어 둔 값을 꺼내 주므로 "
                    + "최대 1~2초 늦을 수 있고, AI 에 묻지 못하면 빈 목록입니다. 서버에서 꺼 두면 enabled 가 false 입니다."
    )
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "기관 사용자가 아님 (ORGANIZATION_NOT_ASSIGNED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<DashboardAiProgressResponse> getAiProgress(@AuthenticationPrincipal String userId) {
        return ApiResponse.success(dashboardAiProgressService.getProgress(userId));
    }
}
