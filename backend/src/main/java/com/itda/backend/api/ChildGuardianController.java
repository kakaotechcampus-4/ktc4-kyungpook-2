package com.itda.backend.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.itda.backend.dto.response.invitation.ChildGuardianResponse;
import com.itda.backend.global.config.OpenApiConfig;
import com.itda.backend.global.exception.ErrorResponse;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.invitation.ChildGuardianService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

// docs/api/api-spec.md O-18(연결된 보호자 목록)·O-19(기관이 보호자 연결 해제).
@RestController
@RequestMapping("/api/v1/institutions/me/children/{childId}/guardians")
@RequiredArgsConstructor
@Tag(name = "보호자 연결", description = "아이와 보호자의 연결 조회·해제")
public class ChildGuardianController {

    private final ChildGuardianService childGuardianService;

    @GetMapping
    @Operation(summary = "연결된 보호자 목록",
            description = "다른 기관을 통해 연결된 보호자도 포함합니다. linkedByThisOrganization 이 true 인 보호자만 "
                    + "이 기관이 해제할 수 있습니다.")
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
    public ApiResponse<List<ChildGuardianResponse>> listGuardians(
            @AuthenticationPrincipal String userId, @PathVariable Long childId) {
        return ApiResponse.success(childGuardianService.listGuardians(userId, childId));
    }

    /** 본문 없는 204 라 {@code ApiResponse} 로 감싸지 않는다 (backend/AGENTS.md). */
    @DeleteMapping("/{guardianUserId}")
    @Operation(summary = "보호자 연결 해제",
            description = "우리 기관이 발급한 코드로 연결된 보호자만 해제할 수 있습니다. 연결은 기관 구분 없는 "
                    + "전역 연결이라 해제하면 모든 기관에서 끊깁니다.")
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
                    description = "가입 미완료 (SIGNUP_NOT_COMPLETED), 기관 소속이 아닌 사용자 "
                            + "(ORGANIZATION_NOT_ASSIGNED), 다른 기관을 통해 연결된 보호자 "
                            + "(GUARDIAN_LINKED_BY_OTHER_ORGANIZATION), CSRF 토큰 누락·불일치 (FORBIDDEN)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "우리 기관 명부에 없는 아이 (CHILD_NOT_FOUND), 연결되지 않은 보호자 (GUARDIAN_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<Void> unlink(
            @AuthenticationPrincipal String userId, @PathVariable Long childId, @PathVariable Long guardianUserId) {
        childGuardianService.unlinkByOrganization(userId, childId, guardianUserId);
        return ResponseEntity.noContent().build();
    }
}
