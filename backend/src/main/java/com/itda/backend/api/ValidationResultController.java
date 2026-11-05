package com.itda.backend.api;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.itda.backend.dto.request.validation.ReviseBlockedEntryRequest;
import com.itda.backend.dto.request.validation.ValidationResolveRequest;
import com.itda.backend.dto.response.validation.ValidationQueueItemResponse;
import com.itda.backend.exception.validation.ValidationResultValidationException;
import com.itda.backend.global.config.OpenApiConfig;
import com.itda.backend.global.exception.ErrorResponse;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.validation.ValidationQueueService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// docs/api/api-spec.md O-24, O-25.
// principal은 MatchingQueueController와 같은 내부 userId다. 기관 식별은 ValidationQueueService가
// UserService.getOrganizationIdOf(userId)로 풀어서 raw_record.institutionId와 비교한다.
@RestController
@RequestMapping("/api/v1/validation-results")
@RequiredArgsConstructor
@Tag(name = "수정 요청 큐", description = "검증에서 막힌(BLOCK) 기록을 선생님이 확인·처리")
public class ValidationResultController {

    private final ValidationQueueService validationQueueService;

    @GetMapping
    @Operation(
            summary = "수정 요청 큐 조회",
            description = "인증된 기관 소속의 BLOCK 판정 기록만 반환합니다. 지금은 status=BLOCK만 지원합니다."
    )
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "지원하지 않는 status (VALIDATION_RESULT_INVALID_REQUEST)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<List<ValidationQueueItemResponse>> getQueue(
            @RequestParam(defaultValue = "BLOCK") String status,
            @AuthenticationPrincipal String userId) {
        // FE는 ?status=BLOCK으로만 부른다(frontend/app/lib/api.ts). 다른 값은 조용히 BLOCK으로
        // 처리하면 화면이 엉뚱한 목록을 보여주므로 명시적으로 거절한다.
        if (!"BLOCK".equalsIgnoreCase(status)) {
            throw new ValidationResultValidationException("unsupported status: " + status);
        }
        return ApiResponse.success(validationQueueService.getBlockedQueue(userId));
    }

    @PostMapping("/{id}/resolve")
    @Operation(
            summary = "이 기록 보류",
            description = "action=hold 만 받습니다. 이 일지의 파이프라인을 여기서 끝냅니다. "
                    + "reupload(수정한 원본 다시 올리기)는 받지 않습니다 — 본문을 고치려면 revise 를 씁니다."
    )
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "처리 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "action 누락·오타·reupload, BLOCK이 아닌 결과, 이미 처리된 기록 "
                            + "(VALIDATION_RESULT_INVALID_REQUEST) · 최신이 아닌 판정 (VALIDATION_RESULT_OUTDATED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "없는 검증 결과이거나 다른 기관 소속 (VALIDATION_RESULT_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<Void> resolve(
            @PathVariable Long id,
            @RequestBody ValidationResolveRequest request,
            @AuthenticationPrincipal String userId) {
        validationQueueService.resolve(id, request.action(), userId);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/revise")
    @Operation(
            summary = "고쳐서 다시 검사",
            description = "막힌 일지의 본문을 고친 본문으로 바꾸고 검증을 다시 받게 합니다. 아이는 그대로입니다. "
                    + "통과하면 요약 대기가 되고, 또 막히면 수정 요청 큐에 최신 사유로 다시 뜹니다."
    )
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "고침 — 다시 검증 대기"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "본문 누락·빈 본문·100000자 초과 (INVALID_REQUEST) · 고치지 않은 본문 "
                            + "(VALIDATION_RESULT_CONTENT_UNCHANGED) · 삭제됐거나 명부에서 빠졌거나 동의가 꺼진 아이 "
                            + "(VALIDATION_RESULT_CHILD_NOT_SHARED) · 최신이 아닌 판정 (VALIDATION_RESULT_OUTDATED) · "
                            + "BLOCK이 아닌 결과·이미 처리된 기록 (VALIDATION_RESULT_INVALID_REQUEST)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "없는 검증 결과이거나 다른 기관 소속 (VALIDATION_RESULT_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<Void> revise(
            @PathVariable Long id,
            @Valid @RequestBody ReviseBlockedEntryRequest request,
            @AuthenticationPrincipal String userId) {
        validationQueueService.revise(id, request.content(), userId);
        return ApiResponse.success(null);
    }
}
