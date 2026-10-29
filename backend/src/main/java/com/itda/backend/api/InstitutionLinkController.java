package com.itda.backend.api;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.itda.backend.dto.request.child.RequestInstitutionLinkRequest;
import com.itda.backend.dto.response.child.InstitutionLinkRequestResponse;
import com.itda.backend.dto.response.organization.InstitutionResponse;
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

// docs/api/api-spec.md G-43(기관 코드 조회)·G-44(기관 코드로 연결 요청).
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "보호자 기관 연결 요청", description = "보호자가 기관 코드로 이미 연결된 아이를 다른 기관에 연결해 달라고 요청")
public class InstitutionLinkController {

    private final InstitutionLinkService institutionLinkService;

    @GetMapping("/institutions/lookup")
    @Operation(summary = "기관 코드 조회",
            description = "기관 코드로 기관을 찾습니다. 조회만 하고 연결하지 않습니다. 공백·하이픈·대소문자는 무시합니다. "
                    + "형식이 틀린 코드와 없는 코드는 같은 404 입니다.")
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
                    description = "가입 미완료 (SIGNUP_NOT_COMPLETED), 보호자가 아닌 사용자 (PARENT_REQUIRED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "일치하는 기관 없음 (INSTITUTION_CODE_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<InstitutionResponse> lookup(@AuthenticationPrincipal String userId, @RequestParam String code) {
        return ApiResponse.success(institutionLinkService.lookup(userId, code));
    }

    @PostMapping("/children/{childId}/institutions")
    @Operation(summary = "기관 코드로 연결 요청",
            description = "아이를 그 기관 명부에 올려 달라고 요청합니다. 기관이 관리번호를 넣어 수락하면 명부에 오르고, "
                    + "그때부터 보호자가 그 기관에 동의할 수 있습니다. 같은 보호자가 다시 요청하면 기다리는 요청을 그대로 돌려줍니다. "
                    + "다른 보호자의 요청과는 따로 남습니다.")
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "요청됨(또는 이미 기다리는 요청)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "code 누락·공백 (INVALID_REQUEST)",
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
                            + "연결되지 않았거나 삭제된 아이 또는 CSRF 토큰 누락·불일치 (FORBIDDEN)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "일치하는 기관 없음 (INSTITUTION_CODE_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "아이가 이미 그 기관 명부에 있음 (INSTITUTION_ALREADY_LINKED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<InstitutionLinkRequestResponse> request(
            @AuthenticationPrincipal String userId,
            @PathVariable Long childId,
            @Valid @RequestBody RequestInstitutionLinkRequest request) {
        return ApiResponse.success(institutionLinkService.request(userId, childId, request.code()));
    }
}
