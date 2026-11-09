package com.itda.backend.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.itda.backend.dto.response.organization.IssuedHandoverResponse;
import com.itda.backend.dto.response.organization.MyInstitutionResponse;
import com.itda.backend.global.config.OpenApiConfig;
import com.itda.backend.global.exception.ErrorResponse;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.organization.OrganizationHandoverService;
import com.itda.backend.service.organization.OrganizationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

// docs/api/api-spec.md O-01(내 기관 정보) · O-84 · O-85(담당자 넘기기, #478).
@RestController
@RequestMapping("/api/v1/institutions/me")
@RequiredArgsConstructor
@Tag(name = "내 기관", description = "기관 담당자의 소속 기관 정보와 담당자 넘기기")
public class OrganizationController {

    private final OrganizationService organizationService;
    private final OrganizationHandoverService organizationHandoverService;

    @GetMapping
    @Operation(summary = "내 기관 정보",
            description = "기관명·유형과 기관 코드입니다. 기관 코드는 보호자에게 알려 주는 고정 8자리이고, 보호자는 이 코드로 "
                    + "이미 다른 기관에 다니는 아이를 이 기관에 연결해 달라고 요청합니다. 코드가 없던 기관은 처음 열 때 만들어집니다.")
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
                    responseCode = "503",
                    description = "동시에 만든 기관 코드와 겹침. 다시 요청 (INSTITUTION_CODE_ISSUE_FAILED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<MyInstitutionResponse> getMine(@AuthenticationPrincipal String userId) {
        return ApiResponse.success(organizationService.getMine(userId));
    }
    @PostMapping("/handover")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "담당자 넘기기 — 인계 코드 발급",
            description = "후임 담당자에게 건넬 일회용 인계 코드(8자, 12시간)를 발급합니다. 후임이 카카오로 로그인해 회원가입에서 이 "
                    + "코드를 넣으면 같은 기관의 담당자가 되고, 이 계정은 탈퇴(카카오 연결 해제 · 식별정보 삭제)로 정리됩니다. 아동 "
                    + "명부와 기록은 기관에 그대로 남습니다. 다시 발급하면 이전 코드는 쓸 수 없습니다. 회원 탈퇴가 꺼져 있으면 404 입니다.")
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
                    description = "가입 미완료 (SIGNUP_NOT_COMPLETED), 기관 소속이 아닌 사용자 (ORGANIZATION_NOT_ASSIGNED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "회원 탈퇴가 꺼져 있음 (WITHDRAWAL_NOT_AVAILABLE)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "이 계정이 이미 탈퇴 처리 중 — 후임이 코드를 썼음 (WITHDRAWAL_IN_PROGRESS)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503",
                    description = "코드를 겹치지 않게 만들지 못함. 다시 요청 (HANDOVER_CODE_ISSUE_FAILED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<IssuedHandoverResponse> issueHandover(@AuthenticationPrincipal String userId) {
        return ApiResponse.success(organizationHandoverService.issue(userId));
    }

    @DeleteMapping("/handover")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "담당자 넘기기 — 인계 코드 취소",
            description = "아직 쓰지 않은 인계 코드를 거둬들입니다. 살아 있는 코드가 없어도 204 입니다.")
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "취소함 (또는 취소할 코드 없음)"),
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
    public void cancelHandover(@AuthenticationPrincipal String userId) {
        organizationHandoverService.cancel(userId);
    }
}
