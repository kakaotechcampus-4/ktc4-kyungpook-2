package com.itda.backend.api;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.itda.backend.dto.response.guardian.GuardianInsightResponse;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.sharing.GuardianInsightService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

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
                    + "sharedWith 는 지금 이 글을 볼 수 있는 다른 기관입니다 — 기관 수신함 · 공유 내역(GET /api/v1/guardians/me/children/{childId}/institution-shares)과 같은 판정"
                    + "(권한이 살아 있고 · 아이가 그 기관 명부에 있고 · 그 기관 동의가 켜져 있음)이고, 기관 이름순입니다. "
                    + "리포트가 없는 지난 주는 kind=quiet 항목(\"특이사항 없음\")으로 섞여 옵니다 — 본문 · 기관 · 근거가 없고 "
                    + "단건 조회(GET /api/v1/guardians/me/insights/{insightId})로 열 수 없으며, approvedAt 은 그 주가 끝난 다음 날 0시입니다 (#470). "
                    + "childId 를 주면 그 아이 것만 반환합니다 (#457)."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "childId 형식 오류 (INVALID_REQUEST)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "보호자 계정이 아님 (PARENT_REQUIRED), "
                            + "연결되지 않았거나 삭제된 아이 (FORBIDDEN)"
            )
    })
    public ApiResponse<List<GuardianInsightResponse>> list(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "이 아이 것만. 없으면 내 모든 아이") @RequestParam(required = false) Long childId) {
        return ApiResponse.success(guardianInsightService.getInsights(userId, childId));
    }

    @GetMapping("/{insightId}")
    @Operation(
            summary = "인사이트 단건",
            description = "목록의 원소 하나와 같은 모양입니다. 처음 열어 보면 그 시각을 남깁니다 — "
                    + "열람 방식이라 '언제 봤나' 를 남기지 않으면 아무 흔적이 없습니다. "
                    + "내게 열리지 않은 글은 404 입니다 — 있는지조차 알려주지 않습니다."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "보호자 계정이 아님 (PARENT_REQUIRED)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "내게 열리지 않은 인사이트 (SHARING_NOT_FOUND)"
            )
    })
    public ApiResponse<GuardianInsightResponse> get(
            @PathVariable Long insightId,
            @AuthenticationPrincipal String userId) {
        return ApiResponse.success(guardianInsightService.getInsight(userId, insightId));
    }
}
