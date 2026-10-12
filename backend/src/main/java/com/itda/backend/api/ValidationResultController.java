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

import com.itda.backend.dto.ValidationQueueItemResponse;
import com.itda.backend.dto.ValidationResolveRequest;
import com.itda.backend.exception.ValidationResultValidationException;
import com.itda.backend.global.config.OpenApiConfig;
import com.itda.backend.global.exception.ErrorResponse;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.ValidationQueueService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
            summary = "수정한 원본 다시 올리기 / 보류",
            description = "action=reupload 또는 hold. 둘 다 이 일지의 파이프라인을 여기서 끝냅니다."
    )
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "처리 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "action 누락·오타, BLOCK이 아닌 결과, 이미 처리된 기록 (VALIDATION_RESULT_INVALID_REQUEST)",
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
}
