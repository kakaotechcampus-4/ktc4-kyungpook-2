package com.itda.backend.api;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.itda.backend.dto.MatchingQueueItemResponse;
import com.itda.backend.dto.MatchingResolveRequest;
import com.itda.backend.global.config.OpenApiConfig;
import com.itda.backend.global.exception.ErrorResponse;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.MatchingResultService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

// docs/api/api-spec.md O-22, O-23.
// principal은 RawRecordController와 같은 내부 userId다. 기관 식별은 MatchingResultService가
// UserService.getOrganizationIdOf(userId)로 풀어서 raw_record.institutionId, child_organization과
// 비교한다 — 컨트롤러는 저장소를 직접 보지 않는다.
@RestController
@RequestMapping("/api/v1/matching-queue")
@RequiredArgsConstructor
@Tag(name = "매칭 확인 큐", description = "AI가 자동 확정하지 못한 매칭 결과를 선생님이 확인·처리")
public class MatchingQueueController {

    private final MatchingResultService matchingResultService;

    @GetMapping
    @Operation(summary = "확인 필요 큐 조회", description = "인증된 기관 소속 건만 반환합니다(status != auto).")
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<List<MatchingQueueItemResponse>> getQueue(
            @AuthenticationPrincipal String userId) {
        return ApiResponse.success(matchingResultService.getQueue(userId));
    }

    @PostMapping("/{id}/resolve")
    @Operation(
            summary = "아이 확정 또는 제외",
            description = "action=assign이면 childId 필수, not_ours면 필요 없습니다."
    )
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "처리 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "action 누락·오타, assign인데 childId 없음, 존재하지 않는 childId, 이미 처리된 결과 (MATCHING_RESULT_INVALID_REQUEST)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "없는 매칭 결과이거나 다른 기관 소속 (MATCHING_RESULT_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<MatchingQueueItemResponse> resolve(
            @PathVariable Long id,
            @RequestBody MatchingResolveRequest request,
            @AuthenticationPrincipal String userId) {
        var resolved = matchingResultService.resolve(
                id, request.action(), request.childId(), userId, userId);
        return ApiResponse.success(resolved);
    }
}
