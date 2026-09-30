package com.itda.backend.api;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.itda.backend.dto.ChildRosterResponse;
import com.itda.backend.global.config.OpenApiConfig;
import com.itda.backend.global.exception.ErrorResponse;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.ChildService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

// docs/api/api-spec.md O-10. 이번 범위는 명부 검색(RosterPicker.tsx)에 필요한
// id/name/birthDate/status만 — O-11(아이 등록), institutions[]/care 등 풀 스펙은 별도 작업.
@RestController
@RequestMapping("/api/v1/institutions/me/children")
@RequiredArgsConstructor
@Tag(name = "담당 아동", description = "기관 소속 아동 명부")
public class ChildController {

    private final ChildService childService;

    @GetMapping
    @Operation(summary = "담당 아동 목록", description = "인증된 기관 소속 아동만 반환합니다.")
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
                    description = "기관 소속이 아닌 사용자 (ORGANIZATION_NOT_ASSIGNED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<List<ChildRosterResponse>> getRoster(@AuthenticationPrincipal String userId) {
        return ApiResponse.success(childService.getRoster(userId));
    }
}
