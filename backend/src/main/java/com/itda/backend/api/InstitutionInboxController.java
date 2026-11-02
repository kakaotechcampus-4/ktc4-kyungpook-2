package com.itda.backend.api;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.itda.backend.dto.response.sharing.InboxDetailResponse;
import com.itda.backend.dto.response.sharing.InboxItemResponse;
import com.itda.backend.global.config.OpenApiConfig;
import com.itda.backend.global.exception.ErrorResponse;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.sharing.InstitutionInboxService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

// principal 은 다른 API 와 같은 내부 userId 다. 기관 담당자인지는 서비스가 UserService 로 본다.
@RestController
@RequestMapping("/api/v1/institutions/me/inbox")
@RequiredArgsConstructor
@Tag(name = "기관 수신함", description = "다른 기관이 승인해 우리 기관에 열린 인사이트를 읽습니다")
public class InstitutionInboxController {

    private final InstitutionInboxService institutionInboxService;

    @GetMapping
    @Operation(
            summary = "수신함 목록",
            description = "다른 기관이 Gate 2 에서 승인해 우리 기관에 열린 인사이트를 최근 열린 순으로 반환합니다. "
                    + "본문은 없습니다 — 단건을 열어야 보이고, 그때 처음 열어 본 시각이 남습니다. readAt 은 아직 "
                    + "안 열었으면 null 입니다. 지금 우리 명부에 없는 아이, 우리 기관 동의가 꺼진 아이의 글은 빠집니다."
    )
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
                    description = "기관 소속 사용자가 아님 (ORGANIZATION_NOT_ASSIGNED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<List<InboxItemResponse>> list(@AuthenticationPrincipal String userId) {
        return ApiResponse.success(institutionInboxService.getInbox(userId));
    }

    @GetMapping("/{insightId}")
    @Operation(
            summary = "수신함 단건",
            description = "목록 항목에 본문(content)을 더해 반환합니다. 처음 열어 보면 그 시각을 우리 기관 기준으로 "
                    + "남깁니다. 열 때마다 권한·명부·우리 기관 동의를 다시 봅니다 — 없는 글, 다른 기관에 열린 글, "
                    + "동의가 꺼졌거나 명부에서 빠진 글은 모두 404 입니다. 있는지조차 알려주지 않습니다."
    )
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
                    description = "기관 소속 사용자가 아님 (ORGANIZATION_NOT_ASSIGNED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "우리 기관이 지금 볼 수 없는 인사이트 (SHARING_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<InboxDetailResponse> get(
            @PathVariable Long insightId,
            @AuthenticationPrincipal String userId) {
        return ApiResponse.success(institutionInboxService.getInboxItem(userId, insightId));
    }
}
