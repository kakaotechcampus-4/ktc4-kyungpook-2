package com.itda.backend.api;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.itda.backend.dto.request.child.AcceptInstitutionLinkRequest;
import com.itda.backend.dto.response.child.ChildRosterResponse;
import com.itda.backend.dto.response.child.PendingLinkRequestResponse;
import com.itda.backend.global.config.OpenApiConfig;
import com.itda.backend.global.exception.ErrorResponse;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.child.InstitutionLinkService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

// docs/api/api-spec.md O-60(연결 요청 목록)·O-61(수락)·O-62(거절).
@RestController
@RequestMapping("/api/v1/institutions/me/link-requests")
@RequiredArgsConstructor
@Tag(name = "기관 연결 요청", description = "보호자가 기관 코드로 보낸 아이 연결 요청의 수락·거절")
public class LinkRequestController {

    private final InstitutionLinkService institutionLinkService;

    @GetMapping
    @Operation(summary = "연결 요청 목록",
            description = "보호자가 기관 코드로 보낸, 기다리는 연결 요청입니다. 같은 아이에 보호자 여럿이 요청했으면 한 항목으로 묶습니다. "
                    + "가장 먼저 요청한 아이부터입니다.")
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공. 요청이 없으면 빈 배열"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "가입 미완료 (SIGNUP_NOT_COMPLETED), 기관 소속이 아닌 사용자 (ORGANIZATION_NOT_ASSIGNED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<List<PendingLinkRequestResponse>> list(@AuthenticationPrincipal String userId) {
        return ApiResponse.success(institutionLinkService.pendingRequests(userId));
    }

    @PostMapping("/{childId}/accept")
    @Operation(summary = "연결 요청 수락",
            description = "아이를 명부에 올립니다. 아이 등록처럼 관리번호가 필수이고 같은 기관 안에서 겹칠 수 없습니다. "
                    + "이 아이에 대한 기다리는 요청은 보호자가 여럿이어도 함께 수락됩니다. 수락은 동의가 아닙니다 — "
                    + "보호자가 이 기관에 동의하기 전에는 consent 가 not_granted 이고 매칭 대상이 아닙니다.")
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수락 성공. 명부에 올라간 아이"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "관리번호 누락·공백·50자 초과 (INVALID_REQUEST)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "가입 미완료 (SIGNUP_NOT_COMPLETED), 기관 소속이 아닌 사용자 "
                            + "(ORGANIZATION_NOT_ASSIGNED), CSRF 토큰 누락·불일치 (FORBIDDEN)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "기다리는 요청이 없는 아이 — 삭제된 아이, 이미 처리된 요청, 요청한 보호자의 연결 해제로 "
                            + "거둬진 요청 (LINK_REQUEST_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "이미 명부에 있는 아이 (INSTITUTION_ALREADY_LINKED), 이미 쓰고 있는 관리번호 "
                            + "(DUPLICATE_EXTERNAL_ID)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<ChildRosterResponse> accept(
            @AuthenticationPrincipal String userId,
            @PathVariable Long childId,
            @Valid @RequestBody AcceptInstitutionLinkRequest request) {
        return ApiResponse.success(institutionLinkService.accept(userId, childId, request.externalId()));
    }

    @PostMapping("/{childId}/reject")
    @Operation(summary = "연결 요청 거절",
            description = "이 아이에 대한 기다리는 요청을 모두 거절합니다. 보호자에게 따로 알리지 않고, 보호자는 다시 요청할 수 있습니다.")
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "거절 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "가입 미완료 (SIGNUP_NOT_COMPLETED), 기관 소속이 아닌 사용자 "
                            + "(ORGANIZATION_NOT_ASSIGNED), CSRF 토큰 누락·불일치 (FORBIDDEN)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "기다리는 요청이 없는 아이 (LINK_REQUEST_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<Void> reject(@AuthenticationPrincipal String userId, @PathVariable Long childId) {
        institutionLinkService.reject(userId, childId);
        return ResponseEntity.noContent().build();
    }
}
