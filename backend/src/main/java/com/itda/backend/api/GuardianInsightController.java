package com.itda.backend.api;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.itda.backend.dto.response.guardian.GuardianInsightResponse;
import com.itda.backend.global.config.OpenApiConfig;
import com.itda.backend.global.exception.ErrorResponse;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.sharing.GuardianInsightService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

// principal 은 다른 API 와 같은 내부 userId 다. 보호자인지는 서비스가 UserService 로 본다.
@RestController
@RequestMapping("/api/v1/guardians/me/insights")
@RequiredArgsConstructor
@Tag(name = "보호자 인사이트", description = "보호자가 받은 인사이트를 읽습니다")
public class GuardianInsightController {

    private final GuardianInsightService guardianInsightService;

    @GetMapping
    @Operation(
            summary = "내게 열린 인사이트 목록",
            description = "담당자가 Gate 2 에서 승인해 보호자에게 열린 인사이트를 승인 시각(approvedAt) 최근 순으로 "
                    + "반환합니다. 나중에 연결된 보호자에게도 연결할 때 지난 승인 글이 열리고, 같은 승인 시각 자리에 놓입니다. "
                    + "sharedWith 는 지금 이 글을 볼 수 있는 다른 기관입니다 — 승인할 때 공유 동의가 켜져 있던 "
                    + "기관이고, 동의를 끄면 빠집니다."
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
                    description = "보호자 계정이 아님 (PARENT_REQUIRED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<List<GuardianInsightResponse>> list(@AuthenticationPrincipal String userId) {
        return ApiResponse.success(guardianInsightService.getInsights(userId));
    }

    @GetMapping("/{insightId}")
    @Operation(
            summary = "인사이트 단건",
            description = "목록의 원소 하나와 같은 모양입니다. 처음 열어 보면 그 시각을 남깁니다 — "
                    + "열람 방식이라 '언제 봤나' 를 남기지 않으면 아무 흔적이 없습니다. "
                    + "내게 열리지 않은 글은 404 입니다 — 있는지조차 알려주지 않습니다."
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
                    description = "보호자 계정이 아님 (PARENT_REQUIRED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "내게 열리지 않은 인사이트 (SHARING_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<GuardianInsightResponse> get(
            @PathVariable Long insightId,
            @AuthenticationPrincipal String userId) {
        return ApiResponse.success(guardianInsightService.getInsight(userId, insightId));
    }
}
