package com.itda.backend.api;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.itda.backend.dto.request.invitation.AcceptInvitationRequest;
import com.itda.backend.dto.response.child.GuardianChildResponse;
import com.itda.backend.dto.response.child.GuardianHomeResponse;
import com.itda.backend.dto.response.invitation.AcceptedInvitationResponse;
import com.itda.backend.global.config.OpenApiConfig;
import com.itda.backend.global.exception.ErrorResponse;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.child.GuardianChildQueryService;
import com.itda.backend.service.invitation.ChildGuardianService;
import com.itda.backend.service.invitation.InvitationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

// docs/api/api-spec.md G-04(초대 코드 수락)·G-05(보호자 본인 연결 해제)·G-10(아이 목록)·G-11(홈).
@RestController
@RequestMapping("/api/v1/guardians/me")
@RequiredArgsConstructor
@Tag(name = "보호자", description = "보호자 본인의 아이 연결·조회")
public class GuardianController {

    private final InvitationService invitationService;
    private final ChildGuardianService childGuardianService;
    private final GuardianChildQueryService guardianChildQueryService;

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

    @GetMapping("/children")
    @Operation(summary = "내 아이 목록",
            description = "이 보호자와 연결된 아이 전부입니다. 동의 전·동의를 모두 끈 아이도 들어 있고, 연결된 순서입니다. "
                    + "institutions 는 아이가 지금 명부에 올라 있는 기관(기관 id 순)과 기관별 동의 상태입니다. "
                    + "동의 상태는 아이 기준이라 어느 보호자의 동의든 살아 있으면 granted 입니다. "
                    + "school·care 는 저장하는 곳이 없어 내려주지 않습니다.")
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공. 연결된 아이가 없으면 빈 배열"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "가입 미완료 (SIGNUP_NOT_COMPLETED), 보호자가 아닌 사용자 (PARENT_REQUIRED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<List<GuardianChildResponse>> children(@AuthenticationPrincipal String userId) {
        return ApiResponse.success(guardianChildQueryService.children(userId));
    }

    @GetMapping("/home")
    @Operation(summary = "보호자 홈",
            description = "아이 하나와 최근 공유 활동입니다. child 는 아이 목록의 한 항목과 같은 모양입니다. "
                    + "최근 공유 활동(activity)은 아직 만드는 곳이 없어 언제나 빈 배열입니다.")
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "childId 누락·형식 오류 (INVALID_REQUEST)",
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
                            + "연결되지 않았거나 삭제된 아이 (FORBIDDEN)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<GuardianHomeResponse> home(@AuthenticationPrincipal String userId, @RequestParam Long childId) {
        return ApiResponse.success(guardianChildQueryService.home(userId, childId));
    }
}
