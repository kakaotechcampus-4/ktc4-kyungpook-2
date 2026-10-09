package com.itda.backend.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.itda.backend.dto.request.RunSummaryRequest;
import com.itda.backend.global.config.OpenApiConfig;
import com.itda.backend.global.exception.ErrorResponse;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.summary.SummaryRunService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// principal 은 다른 기관 API 와 같은 내부 userId 다. 기관은 SummaryRunService 가 UserService 로 푼다.
@RestController
@RequestMapping("/api/v1/summaries")
@RequiredArgsConstructor
@Tag(name = "요약", description = "검증을 통과한 일지를 아동 × 날짜 × 기관 묶음으로 요약")
public class SummaryController {

    private final SummaryRunService summaryRunService;

    @PostMapping("/run")
    @Operation(
            summary = "지금 요약",
            description = "그 아이·날짜의 일지를 다음 날 03:00 마감을 기다리지 않고 요약하도록 요청합니다. "
                    + "요약은 워커가 곧이어 하며, 같은 날짜에 매칭·검증 중인 일지가 있으면 그것이 끝난 뒤에 합니다."
    )
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "요청 접수"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "childId·entryDate 누락·형식 오류 (INVALID_REQUEST), "
                            + "그 아이·날짜에 요약할 일지도 매칭·검증 중인 일지도 없음 (SUMMARY_INVALID_REQUEST)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "없는 아동이거나 다른 기관 소속 (SUMMARY_CHILD_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<Void> run(
            @Valid @RequestBody RunSummaryRequest request,
            @AuthenticationPrincipal String userId) {
        summaryRunService.requestRun(userId, request.childId(), request.entryDateValue());
        return ApiResponse.success(null);
    }
}
