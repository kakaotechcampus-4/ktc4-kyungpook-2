package com.itda.backend.api;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.itda.backend.dto.response.child.OrganizationTimelineEntryResponse;
import com.itda.backend.global.config.OpenApiConfig;
import com.itda.backend.global.exception.ErrorResponse;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.child.OrganizationTimelineQueryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/institutions/me/children/{childId}/timeline")
@RequiredArgsConstructor
@Tag(name = "기관 아동 타임라인", description = "우리 기관이 승인한 요약을 아이 한 명 기준으로 봅니다")
public class OrganizationTimelineController {

    private final OrganizationTimelineQueryService organizationTimelineQueryService;

    @GetMapping
    @Operation(summary = "아동 기록 타임라인",
            description = "우리 기관이 Gate 1 에서 승인한 요약만, 관찰 날짜 최근 순(같은 날은 승인 최근 순)입니다. "
                    + "다른 기관 기록은 나오지 않습니다 — 기관 사이에는 Gate 2 를 지난 인사이트(수신함)로만 넘어갑니다. "
                    + "기록이 없는 날은 항목이 없습니다. 같은 날 승인본이 여럿이면 따로 나옵니다. "
                    + "보호자가 우리 기관 동의를 꺼도 지난 기록은 나옵니다.")
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
                    description = "가입 미완료 (SIGNUP_NOT_COMPLETED), 기관 소속이 아닌 사용자 (ORGANIZATION_NOT_ASSIGNED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "없거나 삭제됐거나 우리 기관 명부에 없는 아이 (CHILD_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<List<OrganizationTimelineEntryResponse>> timeline(
            @AuthenticationPrincipal String userId, @PathVariable Long childId) {
        return ApiResponse.success(organizationTimelineQueryService.timeline(userId, childId));
    }
}
