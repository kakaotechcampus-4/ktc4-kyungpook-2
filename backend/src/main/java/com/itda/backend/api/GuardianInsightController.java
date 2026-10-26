package com.itda.backend.api;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.itda.backend.dto.request.guardian.GuardianShareRequest;
import com.itda.backend.dto.response.guardian.GuardianInsightResponse;
import com.itda.backend.global.config.OpenApiConfig;
import com.itda.backend.global.exception.ErrorResponse;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.sharing.GuardianShareService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// principal 은 다른 API 와 같은 내부 userId 다. 보호자인지는 서비스가 UserService 로 본다.
@RestController
@RequestMapping("/api/v1/guardians/me/insights")
@RequiredArgsConstructor
@Tag(name = "보호자 인사이트", description = "보호자가 받은 인사이트를 읽고, 어느 기관에 열어줄지 고릅니다")
public class GuardianInsightController {

    private final GuardianShareService guardianShareService;

    @GetMapping
    @Operation(
            summary = "내게 열린 인사이트 목록",
            description = "담당자가 Gate 2 에서 승인해 보호자에게 열린 인사이트를 최근 열린 순으로 반환합니다. "
                    + "sharedWith 는 지금 고를 수 있는 기관과 각각 열려 있는지입니다 — 그 아이 명부에 있고 "
                    + "공유 동의가 살아 있는 기관만 나오며, 이 글의 재료를 낸 기관은 빠집니다."
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
                    description = "보호자 계정이 아님 (PARENT_REQUIRED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<List<GuardianInsightResponse>> list(@AuthenticationPrincipal String userId) {
        return ApiResponse.success(guardianShareService.getInsights(userId));
    }

    @GetMapping("/{insightId}")
    @Operation(
            summary = "인사이트 단건",
            description = "목록의 원소 하나와 같은 모양입니다. 처음 열어 보면 그 시각을 남깁니다 — "
                    + "열람 방식이라 '언제 봤나' 를 남기지 않으면 아무 흔적이 없습니다. "
                    + "내게 열리지 않은 글은 404 입니다 — 있는지조차 알려주지 않습니다."
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
                    description = "보호자 계정이 아님 (PARENT_REQUIRED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "내게 열리지 않은 인사이트 (SHARING_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<GuardianInsightResponse> get(
            @PathVariable Long insightId,
            @AuthenticationPrincipal String userId) {
        return ApiResponse.success(guardianShareService.getInsight(userId, insightId));
    }

    @PostMapping("/{insightId}/share")
    @Operation(
            summary = "공유할 기관 고르기",
            description = "이 인사이트를 볼 수 있게 할 기관을 정합니다. **고른 목록이 곧 최종 상태**라 "
                    + "여기 없는 기관은 꺼집니다. 빈 배열은 전부 끈다는 뜻입니다. "
                    + "같은 요청을 두 번 보내도 결과가 같고, 껐다가 다시 켤 수 있습니다 — "
                    + "담당자의 Gate 2 승인과 달리 보호자의 선택은 되돌릴 수 있습니다. "
                    + "고를 수 없는 기관이 하나라도 섞이면 아무것도 반영하지 않고 400 입니다."
    )
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "반영됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "institutionIds 누락 (INVALID_REQUEST), "
                            + "명부에 없거나 동의가 꺼졌거나 이 글의 재료를 낸 기관 (SHARING_NOT_SHAREABLE)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "보호자 계정이 아님 (PARENT_REQUIRED), CSRF 토큰 누락 (FORBIDDEN)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "내게 열리지 않은 인사이트 (SHARING_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<GuardianInsightResponse> share(
            @PathVariable Long insightId,
            @Valid @RequestBody GuardianShareRequest request,
            @AuthenticationPrincipal String userId) {
        return ApiResponse.success(
                guardianShareService.share(userId, insightId, request.institutionIds()));
    }
}
