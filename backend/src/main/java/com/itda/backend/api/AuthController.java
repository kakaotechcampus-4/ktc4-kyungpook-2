package com.itda.backend.api;

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
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인 엔드포인트는 없다. Spring Security 가
 * {@code /oauth2/authorization/kakao} 와 {@code /login/oauth2/code/kakao} 를 직접 제공한다.
 *
 * <p>여기 있는 건 로그인 이후에 필요한 것들이다 — 프론트 라우트 가드가 매 진입마다 부르는
 * 세션 조회와, 출입증 쿠키를 지우는 로그아웃이다. 출입증이 httpOnly 쿠키라 프론트가 스스로
 * 지울 수 없어서, 서버가 지워주지 않으면 사용자는 로그아웃할 방법이 아예 없다.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "인증", description = "카카오 로그인과 로그아웃")
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
                    + "화면에 들어갈 때마다 호출합니다. role 은 org 또는 parent 이며, "
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
                    description = "CSRF 토큰 누락",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, jwtCookie.expire().toString())
                .build();
    }
}
