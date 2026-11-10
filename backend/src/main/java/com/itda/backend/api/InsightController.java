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

import com.itda.backend.dto.request.insight.Gate2DecisionRequest;
import com.itda.backend.dto.response.insight.Gate2DecisionResponse;
import com.itda.backend.dto.response.insight.Gate2InsightResponse;
import com.itda.backend.dto.response.insight.InsightProgressResponse;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.insight.InsightGate2QueryService;
import com.itda.backend.service.insight.InsightGate2Service;
import com.itda.backend.service.insight.InsightProgressService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/insights")
@RequiredArgsConstructor
@Tag(name = "인사이트", description = "승인된 요약을 겹쳐 만든 인사이트를 교사가 Gate 2 에서 발송 전 검토")
public class InsightController {

    private final InsightGate2QueryService insightGate2QueryService;
    private final InsightGate2Service insightGate2Service;
    private final InsightProgressService insightProgressService;

    @GetMapping
    @Operation(
            summary = "Gate 2 검토 목록",
            description = "우리 기관이 승인 주체인 인사이트를 최신 것부터 반환합니다. 승인 주체는 근거를 낸 기관 중 "
                    + "기관 종류 우선순위(학교 > 센터 > 활동지원사, 동점이면 작은 기관 ID)로 정해집니다. "
                    + "같은 아동·같은 기간은 최신 회차만 보입니다. needsReview 는 지금 항상 true 입니다 — 본문에 "
                    + "다른 아이 이름이 없다는 보장이 없고 교사가 검증 없이 쓴 문장이 근거로 섞일 수 있습니다. "
                    + "targets 는 읽기 전용 참고이고, 수신 기관은 승인 뒤 보호자가 정합니다."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "지원하지 않는 gate2Status (INSIGHT_INVALID_REQUEST)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "기관 소속이 아닌 사용자 (ORGANIZATION_NOT_ASSIGNED)"
            )
    })
    public ApiResponse<List<Gate2InsightResponse>> list(
            @Parameter(schema = @Schema(allowableValues = {"pending", "approved", "hold"}))
            @RequestParam(defaultValue = "pending") String gate2Status,
            @AuthenticationPrincipal String userId) {
        return ApiResponse.success(insightGate2QueryService.getInsights(userId, gate2Status));
    }

    @GetMapping("/progress")
    @Operation(
            summary = "인사이트 진행 현황",
            description = "우리 기관이 최근 Gate 1 에서 승인한 기록이 들어가는 아이·주마다 분석이 어디까지 왔는지 "
                    + "돌려줍니다. 최근 주부터, 같은 주는 아이 이름 순입니다. 지금 우리 명부에 있고 공유 동의가 켜진 "
                    + "아이만 나옵니다. message 는 화면에 그대로 쓰는 문장입니다."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "기관 소속이 아닌 사용자 (ORGANIZATION_NOT_ASSIGNED)"
            )
    })
    public ApiResponse<List<InsightProgressResponse>> progress(@AuthenticationPrincipal String userId) {
        return ApiResponse.success(insightProgressService.getProgress(userId));
    }

    @GetMapping("/{insightId}")
    @Operation(
            summary = "인사이트 단건",
            description = "목록의 원소 하나와 같은 모양입니다. 인가 규칙도 같습니다 — 근거를 내지 않은 기관에는 "
                    + "404 로 응답해 존재 여부를 알려주지 않습니다. 옛 회차도 ID 로 직접 물으면 보이지만 승인은 막힙니다."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "기관 소속이 아닌 사용자 (ORGANIZATION_NOT_ASSIGNED), "
                            + "근거는 냈지만 승인 주체가 아님 (INSIGHT_NOT_PRIMARY_SOURCE)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "없는 인사이트이거나 근거를 내지 않은 기관 (INSIGHT_NOT_FOUND)"
            )
    })
    public ApiResponse<Gate2InsightResponse> get(
            @PathVariable Long insightId,
            @AuthenticationPrincipal String userId) {
        return ApiResponse.success(insightGate2QueryService.getInsight(userId, insightId));
    }

    @PostMapping("/{insightId}/gate2")
    @Operation(
            summary = "Gate 2 결정",
            description = "인사이트를 승인하거나 보류합니다. 승인하면 보호자에게 열람 권한이 생기고, 수신 기관은 그 뒤 "
                    + "보호자가 고릅니다 — 기관이 수신처를 고르지 않으므로 요청에 대상 기관을 담지 않습니다. "
                    + "보호자가 한 명도 연결돼 있지 않으면 승인은 되지만 아무에게도 보이지 않습니다(오류 아님). "
                    + "승인·보류 모두 되돌릴 수 없고, Gate 1 과 달리 보류 해제가 없습니다."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "결정 반영"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "decision 누락·허용되지 않은 값 (INVALID_REQUEST)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "기관 소속이 아닌 사용자 (ORGANIZATION_NOT_ASSIGNED), "
                            + "근거는 냈지만 승인 주체가 아님 (INSIGHT_NOT_PRIMARY_SOURCE), CSRF 토큰 누락 (FORBIDDEN)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "없는 인사이트이거나 근거를 내지 않은 기관 (INSIGHT_NOT_FOUND)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "이미 승인·보류됨 (INSIGHT_ALREADY_REVIEWED), "
                            + "같은 기간의 새 회차가 만들어짐 (INSIGHT_SUPERSEDED)"
            )
    })
    public ApiResponse<Gate2DecisionResponse> decide(
            @PathVariable Long insightId,
            @Valid @RequestBody Gate2DecisionRequest request,
            @AuthenticationPrincipal String userId) {
        return ApiResponse.success(insightGate2Service.decide(userId, insightId, request.action()));
    }
}
