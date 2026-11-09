package com.itda.backend.api;

import java.util.List;

import jakarta.validation.constraints.Min;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.itda.backend.dto.response.child.GuardianJournalDetailResponse;
import com.itda.backend.dto.response.child.GuardianJournalResponse;
import com.itda.backend.global.config.OpenApiConfig;
import com.itda.backend.global.exception.ErrorResponse;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.child.GuardianJournalQueryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

// docs/api/api-spec.md G-20(일지 목록)·G-21(일지 상세). 두 경로의 앞부분이 달라 /api/v1 에 붙인다.
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Validated
@Tag(name = "보호자 일지", description = "교사가 1차 검토에서 승인한 요약을 보호자가 읽습니다")
public class GuardianJournalController {

    private final GuardianJournalQueryService guardianJournalQueryService;

    @GetMapping("/children/{childId}/journal")
    @Operation(summary = "일지 목록",
            description = "이 아이의 일지 전부입니다. 일지 한 건은 교사가 1차 검토에서 승인한 요약 하나입니다. "
                    + "관찰 날짜 최근 순, 같은 날은 승인 최근 순입니다. 동의가 꺼졌거나 명부에서 빠진 기관의 지난 일지도 "
                    + "나옵니다. isNew 는 승인된 지 24시간이 안 됐다는 뜻입니다. "
                    + "limit 를 주면 같은 순서로 앞에서 그만큼만 반환합니다 (#458).")
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "limit 가 1 보다 작거나 숫자가 아님 (INVALID_REQUEST)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "가입 미완료 (SIGNUP_NOT_COMPLETED), 보호자가 아닌 사용자 (PARENT_REQUIRED), "
                            + "이 보호자와 연결되지 않았거나 삭제된 아이 (FORBIDDEN)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<List<GuardianJournalResponse>> journal(
            @AuthenticationPrincipal String userId, @PathVariable Long childId,
            @Parameter(description = "앞에서 이만큼만. 없으면 전부") @RequestParam(required = false) @Min(1) Integer limit) {
        return ApiResponse.success(guardianJournalQueryService.journal(userId, childId, limit));
    }

    @GetMapping("/journal/{id}")
    @Operation(summary = "일지 상세",
            description = "목록의 원소 하나에 옆 일지를 더한 모양입니다. prevId(더 예전) · nextId(더 최근) · "
                    + "sameDay(같은 날 다른 기관 일지)는 목록과 같은 순서로 정합니다 (#458). "
                    + "볼 수 없는 일지는 404 입니다 — 없는 일지, 남의 아이 일지, "
                    + "연결을 끊은 아이·삭제된 아이의 일지를 나누지 않습니다.")
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
                    description = "가입 미완료 (SIGNUP_NOT_COMPLETED), 보호자가 아닌 사용자 (PARENT_REQUIRED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "볼 수 없는 일지 (JOURNAL_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<GuardianJournalDetailResponse> entry(
            @AuthenticationPrincipal String userId, @PathVariable Long id) {
        return ApiResponse.success(guardianJournalQueryService.entry(userId, id));
    }
}
