package com.itda.backend.api;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.itda.backend.dto.request.invitation.AcceptInvitationRequest;
import com.itda.backend.dto.response.invitation.AcceptedInvitationResponse;
import com.itda.backend.global.config.OpenApiConfig;
import com.itda.backend.global.exception.ErrorResponse;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.ChildGuardianService;
import com.itda.backend.service.InvitationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

// docs/api/api-spec.md G-04(초대 코드 수락)·G-05(보호자 본인 연결 해제).
@RestController
@RequestMapping("/api/v1/guardians/me")
@RequiredArgsConstructor
@Tag(name = "보호자", description = "보호자 본인의 아이 연결")
public class GuardianController {

    private final InvitationService invitationService;
    private final ChildGuardianService childGuardianService;

    @PostMapping("/invitations/accept")
    @Operation(summary = "초대 코드 수락",
            description = "기관에게 받은 코드로 아이와 연결됩니다. 하이픈·공백·소문자는 무시합니다. "
                    + "연결은 동의가 아닙니다 — 아동 상태는 바뀌지 않고, childStatus 는 아동의 실제 현재 상태입니다.")
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "연결 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "코드 누락 (INVALID_REQUEST), 형식이 틀렸거나 없거나 만료·사용·취소된 코드 "
                            + "(INVALID_INVITE_CODE)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "가입 미완료 (SIGNUP_NOT_COMPLETED), 보호자가 아닌 사용자 (PARENT_REQUIRED), "
                            + "CSRF 토큰 누락·불일치 (FORBIDDEN)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "이미 연결된 아이. 코드는 쓰지 않은 채 남음 (LINK_ALREADY_EXISTS)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<AcceptedInvitationResponse> accept(
            @AuthenticationPrincipal String userId, @Valid @RequestBody AcceptInvitationRequest request) {
        return ApiResponse.success(invitationService.accept(userId, request.code()));
    }

    /** 본문 없는 204 라 {@code ApiResponse} 로 감싸지 않는다 (backend/AGENTS.md). */
    @DeleteMapping("/children/{childId}")
    @Operation(summary = "아이 연결 해제", description = "보호자 본인이 아이와의 연결을 끊습니다.")
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "해제 성공. 본문 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "가입 미완료 (SIGNUP_NOT_COMPLETED), 보호자가 아닌 사용자 (PARENT_REQUIRED), "
                            + "연결되지 않은 아이 또는 CSRF 토큰 누락·불일치 (FORBIDDEN)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<Void> unlink(@AuthenticationPrincipal String userId, @PathVariable Long childId) {
        childGuardianService.unlinkBySelf(userId, childId);
        return ResponseEntity.noContent().build();
    }
}
