package com.itda.backend.api;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.itda.backend.dto.ChildRosterResponse;
import com.itda.backend.dto.request.RegisterChildRequest;
import com.itda.backend.dto.response.RegisteredChildResponse;
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

// docs/api/api-spec.md O-10(명부)·O-11(아이 등록). 명부는 RosterPicker.tsx에 필요한
// id/name/birthDate/status만 — institutions[]/care 등 풀 스펙과 등록 시 보호자 초대 코드는 별도 작업.
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

    @PostMapping
    @Operation(summary = "아이 등록",
            description = "인증된 기관에 아이를 등록합니다. 등록 직후 상태는 pending_consent 입니다. "
                    + "birthDate 는 yyyy-MM-dd 문자열만 받습니다.")
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "등록 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "이름 누락·100자 초과, 생년월일 형식 오류·없는 날짜·미래 날짜 (INVALID_REQUEST)",
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
            )
    })
    public ResponseEntity<ApiResponse<RegisteredChildResponse>> register(
            @AuthenticationPrincipal String userId, @Valid @RequestBody RegisterChildRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(childService.register(userId, request)));
    }
}
