package com.itda.backend.api;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.itda.backend.dto.response.checklist.GuardianChecklistResponse;
import com.itda.backend.exception.checklist.ChecklistErrorCode;
import com.itda.backend.exception.checklist.ChecklistException;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.checklist.GuardianChecklistService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/children/{childId}/checklists")
@RequiredArgsConstructor
@Tag(name = "보호자 준비물·확인사항", description = "기관이 보낸 준비물 · 확인사항을 보고 확인합니다")
public class GuardianChecklistController {

    private static final String NEEDS_CHECK = "needs_check";

    private final GuardianChecklistService guardianChecklistService;

    @GetMapping
    @Operation(summary = "받은 준비물·확인사항",
            description = "확인 안 한 요청 전부(최신순) 뒤에 확인한 요청 최근 20개(최신순)를 줍니다. 기관이 내린 요청과 아이가 그 기관 "
                    + "명부에서 빠진 요청은 보이지 않습니다. status=needs_check 면 확인 안 한 것만 줍니다(알림 벨 · 설정 개수용).")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "status 가 needs_check 가 아님 (INVALID_REQUEST)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "보호자가 아님 (PARENT_REQUIRED), 약관 미동의 (TERMS_NOT_AGREED), "
                            + "이 보호자와 연결되지 않았거나 삭제된 아이 (FORBIDDEN)"
            )
    })
    public ApiResponse<List<GuardianChecklistResponse>> list(
            @AuthenticationPrincipal String userId,
            @PathVariable Long childId,
            @Parameter(description = "needs_check 면 확인 안 한 것만", schema = @Schema(allowableValues = NEEDS_CHECK))
            @RequestParam(required = false) String status) {
        if (status != null && !NEEDS_CHECK.equals(status)) {
            throw new ChecklistException(ChecklistErrorCode.INVALID_CHECKLIST_STATUS);
        }
        return ApiResponse.success(guardianChecklistService.list(userId, childId, status != null));
    }

    @PostMapping("/{checklistId}/confirm")
    @Operation(summary = "준비물·확인사항 확인",
            description = "이 아이 몫으로 확인합니다. 보호자 중 한 명이 확인하면 끝이고 되돌리지 않습니다. 이미 다른 보호자가 확인했으면 "
                    + "그 확인(누가 · 언제)을 그대로 돌려줍니다. 기관이 내렸거나 아이가 그 기관 명부에서 빠진 요청은 404 입니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "확인됨(이미 확인된 것 포함)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "보호자가 아님 (PARENT_REQUIRED), 약관 미동의 (TERMS_NOT_AGREED), "
                            + "이 보호자와 연결되지 않았거나 삭제된 아이 · CSRF 토큰 누락 (FORBIDDEN)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "이 아이가 받지 않았거나, 기관이 내렸거나, 명부에서 빠진 요청 (CHECKLIST_NOT_FOUND)"
            )
    })
    public ApiResponse<GuardianChecklistResponse> confirm(
            @AuthenticationPrincipal String userId, @PathVariable Long childId, @PathVariable Long checklistId) {
        return ApiResponse.success(guardianChecklistService.confirm(userId, childId, checklistId));
    }
}
