package com.itda.backend.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.itda.backend.dto.response.agent.AiStatusResponse;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.agent.AiStatusService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

// docs/api/api-spec.md O-82.
@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
@Tag(name = "시스템 상태", description = "기관 화면이 보는 AI 상태")
public class SystemController {

    private final AiStatusService aiStatusService;

    @GetMapping("/ai-status")
    @Operation(
            summary = "AI 상태",
            description = "워커가 본 AI /health 실패가 15초 이상 이어지거나 워커가 재시도 끝에 AI 를 부르지 못해 멈추면 "
                    + "available=false 와 그 장애가 처음 보인 시각(since)을 돌려줍니다. 기관 사용자만 볼 수 있습니다."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "기관 사용자가 아님 (ORGANIZATION_NOT_ASSIGNED)"
            )
    })
    public ApiResponse<AiStatusResponse> getAiStatus(@AuthenticationPrincipal String userId) {
        return ApiResponse.success(aiStatusService.getStatus(userId));
    }
}
