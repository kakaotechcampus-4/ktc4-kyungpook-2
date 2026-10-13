package com.itda.backend.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.itda.backend.dto.response.invitation.IssuedInvitationResponse;
import com.itda.backend.dto.response.invitation.LiveInvitationResponse;
import com.itda.backend.global.config.OpenApiConfig;
import com.itda.backend.global.exception.ErrorResponse;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.InvitationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

// docs/api/api-spec.md O-15(추가 발급)·O-16(쓸 수 있는 코드 목록)·O-17(취소). 등록 직후 발급은 O-11 이 한다.
@RestController
@RequestMapping("/api/v1/institutions/me/children/{childId}/invitations")
@RequiredArgsConstructor
@Tag(name = "보호자 초대", description = "기관이 보호자에게 보내는 일회용 초대 코드")
public class InvitationController {

    private final InvitationService invitationService;

    @PostMapping
    @Operation(summary = "초대 코드 추가 발급",
            description = "우리 기관 명부의 아이에게 새 초대 코드를 발급합니다. 코드는 10분 동안 한 번만 쓸 수 있고, "
                    + "한 아이에 쓸 수 있는 코드가 여러 개 있어도 됩니다.")
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "발급 성공"),
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
                    description = "없거나 삭제됐거나 우리 기관 명부에 없는 아이 (CHILD_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503",
                    description = "동시에 발급된 코드와 겹침. 다시 요청 (INVITE_CODE_ISSUE_FAILED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<ApiResponse<IssuedInvitationResponse>> issue(
            @AuthenticationPrincipal String userId, @PathVariable Long childId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(invitationService.issueFor(userId, childId)));
    }

    @GetMapping
    @Operation(summary = "쓸 수 있는 초대 코드 목록",
            description = "아직 수락·취소·만료되지 않은 코드만 최근 발급 순으로 반환합니다.")
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
                    description = "가입 미완료 (SIGNUP_NOT_COMPLETED), 기관 소속이 아닌 사용자 (ORGANIZATION_NOT_ASSIGNED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "없거나 삭제됐거나 우리 기관 명부에 없는 아이 (CHILD_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<List<LiveInvitationResponse>> listLive(
            @AuthenticationPrincipal String userId, @PathVariable Long childId) {
        return ApiResponse.success(invitationService.listLive(userId, childId));
    }

    /** 본문 없는 204 라 {@code ApiResponse} 로 감싸지 않는다 (backend/AGENTS.md). */
    @DeleteMapping("/{invitationId}")
    @Operation(summary = "초대 코드 취소", description = "아직 쓸 수 있는 코드를 거둬들입니다.")
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "취소 성공. 본문 없음"),
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
                    description = "우리 기관 명부에 없는 아이 (CHILD_NOT_FOUND), 없거나 이미 쓸 수 없는 코드 "
                            + "(INVITATION_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<Void> cancel(
            @AuthenticationPrincipal String userId, @PathVariable Long childId, @PathVariable Long invitationId) {
        invitationService.cancel(userId, childId, invitationId);
        return ResponseEntity.noContent().build();
    }
}
