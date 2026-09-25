package com.itda.backend.api;

import com.itda.backend.dto.request.SignupRequest;
import com.itda.backend.dto.response.CurrentUserResponse;
import com.itda.backend.global.config.OpenApiConfig;
import com.itda.backend.global.exception.ErrorResponse;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인 엔드포인트는 없다. Spring Security 가
 * {@code /oauth2/authorization/kakao} 와 {@code /login/oauth2/code/kakao} 를 직접 제공한다.
 *
 * <p>여기 있는 건 로그인 이후에 필요한 것들이다 — 프론트 라우트 가드가 매 진입마다 부르는
 * 세션 조회, 역할을 정하는 회원가입, 출입증 쿠키를 지우는 로그아웃이다. 출입증이 httpOnly 쿠키라 프론트가 스스로
 * 지울 수 없어서, 서버가 지워주지 않으면 사용자는 로그아웃할 방법이 아예 없다.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "인증", description = "카카오 로그인 이후의 세션 조회·회원가입·로그아웃")
public class AuthController {

    private final JwtCookie jwtCookie;
    private final UserService userService;

    public AuthController(JwtCookie jwtCookie, UserService userService) {
        this.jwtCookie = jwtCookie;
        this.userService = userService;
    }

    @GetMapping("/me")
    @Operation(
            summary = "내 세션 조회",
            description = "로그인한 사용자의 역할과 소속 기관을 반환합니다. 프론트 라우트 가드가 "
                    + "화면에 들어갈 때마다 호출합니다. signupCompleted 는 항상 포함되며 false 면 "
                    + "가입 미완료입니다(role 없음). role 은 org 또는 parent 이며, "
                    + "institutionId 는 role 이 org 일 때만 포함됩니다."
    )
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "비로그인이거나 출입증이 더 이상 유효한 사용자를 가리키지 않음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResponse<CurrentUserResponse> me(@AuthenticationPrincipal String userId) {
        return ApiResponse.success(userService.getCurrentUser(userId));
    }

    @PostMapping("/signup")
    @Operation(
            summary = "회원가입",
            description = "카카오 로그인만 한 사용자의 역할을 확정합니다. 보호자는 role 만, 기관은 "
                    + "organizationName·organizationType·businessNumber(숫자 10자리, 진위 검증 없음)를 함께 "
                    + "보냅니다. 기관이면 기관을 새로 만들고 소속시킵니다. 응답은 /auth/me 의 가입 완료 응답과 "
                    + "같습니다. 요청 전에 받은 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더에 포함해야 합니다."
    )
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "가입 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "role 누락·잘못된 값, 기관 필드 누락, 사업자등록번호 형식 오류 (INVALID_REQUEST)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "비로그인이거나 출입증이 더 이상 유효한 사용자를 가리키지 않음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "CSRF 토큰 누락 또는 불일치 (FORBIDDEN)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "이미 가입 완료 (ALREADY_SIGNED_UP) 또는 이미 등록된 사업자등록번호 "
                            + "(DUPLICATE_BUSINESS_NUMBER)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ApiResponse<CurrentUserResponse>> signup(
            @AuthenticationPrincipal String userId, @Valid @RequestBody SignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(userService.completeSignup(userId, request)));
    }

    /**
     * 본문 없는 204 라 {@code ApiResponse} 로 감싸지 않는다
     * (backend/AGENTS.md, docs/api/api-conventions.md).
     */
    @PostMapping("/logout")
    @Operation(
            summary = "로그아웃",
            description = "access_token 쿠키를 만료시킵니다. 요청 전에 받은 XSRF-TOKEN 쿠키 값을 "
                    + "X-XSRF-TOKEN 헤더에 포함해야 합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "204", description = "로그아웃 성공. 본문 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "CSRF 토큰 누락 또는 불일치 (FORBIDDEN)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, jwtCookie.expire().toString())
                .build();
    }
}
