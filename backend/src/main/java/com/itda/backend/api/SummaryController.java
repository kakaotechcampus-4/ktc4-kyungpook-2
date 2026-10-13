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

import com.itda.backend.dto.request.summary.Gate1DecisionRequest;
import com.itda.backend.dto.request.summary.RunSummaryRequest;
import com.itda.backend.dto.response.summary.Gate1DecisionResponse;
import com.itda.backend.dto.response.summary.Gate1SummaryResponse;
import com.itda.backend.global.config.OpenApiConfig;
import com.itda.backend.global.exception.ErrorResponse;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.approval.SummaryGate1QueryService;
import com.itda.backend.service.approval.SummaryGate1Service;
import com.itda.backend.service.summary.SummaryRunService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// principal 은 다른 기관 API 와 같은 내부 userId 다. 기관은 서비스가 UserService 로 푼다.
@RestController
@RequestMapping("/api/v1/summaries")
@RequiredArgsConstructor
@Tag(name = "요약", description = "검증을 통과한 일지를 아동 × 날짜 × 기관 묶음으로 요약하고, 교사가 Gate 1 에서 검토")
public class SummaryController {

    private final SummaryRunService summaryRunService;
    private final SummaryGate1Service summaryGate1Service;
    private final SummaryGate1QueryService summaryGate1QueryService;

    @GetMapping
    @Operation(
            summary = "Gate 1 검토 목록 · 보류함",
            description = "우리 기관의 요약을 오래된 날짜부터 반환합니다. gate1Status=pending 이면 검토 대기, hold 면 보류함입니다. "
                    + "요약 본문, 다른 아이 이름 경고(needsReview·reviewReasons), 요약에 안 들어간 일지와 원문, "
                    + "요약에 들어간 원본의 검증 REVIEW 사유를 함께 줍니다. 응답 모양은 화면 확정 뒤 바뀔 수 있습니다."
    )
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "지원하지 않는 gate1Status (SUMMARY_INVALID_REQUEST)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<List<Gate1SummaryResponse>> list(
            @Parameter(schema = @Schema(allowableValues = {"pending", "hold"}))
            @RequestParam(defaultValue = "pending") String gate1Status,
            @AuthenticationPrincipal String userId) {
        return ApiResponse.success(summaryGate1QueryService.getSummaries(userId, gate1Status));
    }

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

    @PostMapping("/{summaryId}/gate1")
    @Operation(
            summary = "Gate 1 결정",
            description = "요약을 승인·수정 후 승인·보류·제거합니다. 승인할 때 editedContent 를 보내 AI 원문과 한 글자라도 "
                    + "다르면 수정 후 승인(CORRECTED)이고, 고친 본문이 아동 맥락에 올라갑니다. 승인하면 그 요약의 일지가 "
                    + "요약에 안 들어간 것까지 모두 완료되고, 보류하면 함께 보류되며(보류함에서 해제 가능), 제거하면 "
                    + "모두 삭제 표시됩니다. 승인과 제거는 되돌릴 수 없습니다. 응답 모양은 화면 확정 뒤 바뀔 수 있습니다."
    )
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "결정 반영"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "decision 누락·허용되지 않은 값, 빈 editedContent, 승인이 아닌데 editedContent "
                            + "(INVALID_REQUEST)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "없는 요약이거나 다른 기관 요약 (SUMMARY_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "이미 처리된 요약 (SUMMARY_ALREADY_REVIEWED), "
                            + "새 일지를 반영해 다시 요약하는 중 (SUMMARY_REGENERATING)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<Gate1DecisionResponse> decide(
            @PathVariable Long summaryId,
            @Valid @RequestBody Gate1DecisionRequest request,
            @AuthenticationPrincipal String userId) {
        return ApiResponse.success(
                summaryGate1Service.decide(userId, summaryId, request.action(), request.editedContent()));
    }

    @PostMapping("/{summaryId}/reopen")
    @Operation(
            summary = "보류 해제",
            description = "보류함의 요약을 다시 Gate 1 검토 대기로 돌립니다. 그 요약의 일지도 함께 돌아옵니다."
    )
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "보류 해제"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "없는 요약이거나 다른 기관 요약 (SUMMARY_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "보류된 요약이 아님 (SUMMARY_NOT_HELD)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<Gate1DecisionResponse> reopen(
            @PathVariable Long summaryId,
            @AuthenticationPrincipal String userId) {
        return ApiResponse.success(summaryGate1Service.reopen(userId, summaryId));
    }
}

