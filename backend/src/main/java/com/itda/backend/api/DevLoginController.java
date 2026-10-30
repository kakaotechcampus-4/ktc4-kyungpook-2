package com.itda.backend.api;

import com.itda.backend.domain.user.User;
import com.itda.backend.global.exception.ErrorResponse;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.service.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 개발·테스트 전용 로그인. 카카오 계정 없이 여러 회원으로 가입·초대·동의 흐름을 따라가 보려고 둔다.
 *
 * <p>{@code as} 에 {@code dev:} 를 붙여 카카오 회원번호 자리에 넣는다. 실제 카카오 회원번호는 숫자뿐이라
 * 실제 회원과 겹치지 않는다. 그 뒤로는 {@link com.itda.backend.global.security.oauth.OAuth2LoginSuccessHandler}
 * 와 똑같이 회원을 찾거나 만들고, 출입증을 심고, 같은 주소로 돌려보낸다.
 *
 * <p>{@code app.auth.dev-login.enabled=true} 일 때만 빈이 생긴다. 꺼져 있으면 경로 자체가 없어 404 다.
 * 배포 서버는 테스트용으로 compose 가 켜 둔다. 이름만 알면 누구든 그 회원으로 들어올 수 있으므로
 * 정식 오픈 전에는 끈다.
 *
 * <p>주소창에 바로 넣어 쓰려고 GET 이다. 그 대가로 CSRF 검사를 받지 않는다 — 남의 사이트가 이 주소를 띄우면
 * 방문자가 개발용 회원으로 로그인된다. 켜 둔 환경에서만 생기는 문제라 감수한다.
 */
@RestController
@Validated
@RequestMapping("/api/v1/dev")
@ConditionalOnProperty(name = "app.auth.dev-login.enabled", havingValue = "true")
@Tag(name = "개발용 로그인", description = "카카오 없이 테스트 회원으로 로그인합니다. 개발·테스트 환경에서만 켭니다.")
public class DevLoginController {

    private static final String KAKAO_ID_PREFIX = "dev:";

    private final UserService userService;
    private final JwtProvider jwtProvider;
    private final JwtCookie jwtCookie;
    private final URI redirectUri;

    public DevLoginController(
            UserService userService,
            JwtProvider jwtProvider,
            JwtCookie jwtCookie,
            @Value("${app.auth.success-redirect:http://localhost:3000/oauth/success}") String redirectUri
    ) {
        this.userService = userService;
        this.jwtProvider = jwtProvider;
        this.jwtCookie = jwtCookie;
        this.redirectUri = URI.create(redirectUri);
    }

    /** 본문 없는 302 라 {@code ApiResponse} 로 감싸지 않는다. */
    @GetMapping("/login")
    @Operation(
            summary = "개발용 로그인",
            description = "as 이름의 테스트 회원으로 로그인합니다. 처음 쓰는 이름이면 가입 미완료 회원을 만들고, "
                    + "카카오 로그인과 같은 access_token 쿠키를 심은 뒤 로그인 성공 화면으로 보냅니다. "
                    + "app.auth.dev-login.enabled=true 일 때만 존재합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "302",
                    description = "로그인 성공. 본문 없음",
                    headers = {
                            @Header(name = HttpHeaders.LOCATION, description = "로그인 성공 화면(app.auth.success-redirect)"),
                            @Header(name = HttpHeaders.SET_COOKIE, description = "access_token 출입증 쿠키(httpOnly)")
                    }),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "as 누락 또는 형식 오류 — 영문 소문자·숫자·하이픈 1~20자 (INVALID_REQUEST)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> login(
            @Parameter(description = "테스트 회원 이름. 영문 소문자·숫자·하이픈 1~20자", example = "parent1")
            @RequestParam("as") @Pattern(regexp = "^[a-z0-9-]{1,20}$") String account
    ) {
        User user = userService.findOrCreateByKakaoId(KAKAO_ID_PREFIX + account, account);
        String token = jwtProvider.createToken(String.valueOf(user.getId()));
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(redirectUri)
                .header(HttpHeaders.SET_COOKIE, jwtCookie.issue(token).toString())
                .build();
    }
}
