package com.itda.backend.api;

import com.itda.backend.dto.request.user.AgreeTermsRequest;
import com.itda.backend.dto.request.user.SignupRequest;
import com.itda.backend.dto.response.user.CurrentUserResponse;
import com.itda.backend.dto.response.user.TermsAgreementResponse;
import com.itda.backend.dto.response.user.WithdrawalResponse;
import com.itda.backend.exception.user.UserErrorCode;
import com.itda.backend.exception.user.UserException;
import com.itda.backend.global.config.OpenApiConfig;
import com.itda.backend.global.exception.ErrorResponse;
import com.itda.backend.global.jwt.AccessToken;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.user.RevokedSessionService;
import com.itda.backend.service.user.UserService;
import com.itda.backend.service.user.WithdrawalService;
import com.itda.backend.service.user.WithdrawalSteps;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인 엔드포인트는 없다. Spring Security 가
 * {@code /oauth2/authorization/kakao} 와 {@code /login/oauth2/code/kakao} 를 직접 제공한다.
 *
 * <p>여기 있는 건 로그인 이후에 필요한 것들이다 — 프론트 라우트 가드가 매 진입마다 부르는
 * 세션 조회, 역할을 정하는 회원가입, 약관 동의, 출입증 쿠키를 지우는 로그아웃이다. 출입증이 httpOnly 쿠키라 프론트가 스스로
 * 지울 수 없어서, 서버가 지워주지 않으면 사용자는 로그아웃할 방법이 아예 없다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "인증", description = "카카오 로그인 이후의 세션 조회·회원가입·약관 동의·로그아웃·회원 탈퇴")
public class AuthController {

    private final JwtCookie jwtCookie;
    private final JwtProvider jwtProvider;
    private final UserService userService;
    private final RevokedSessionService revokedSessionService;
    private final WithdrawalService withdrawalService;

    public AuthController(
            JwtCookie jwtCookie,
            JwtProvider jwtProvider,
            UserService userService,
            RevokedSessionService revokedSessionService,
            WithdrawalService withdrawalService
    ) {
        this.jwtCookie = jwtCookie;
        this.jwtProvider = jwtProvider;
        this.userService = userService;
        this.revokedSessionService = revokedSessionService;
        this.withdrawalService = withdrawalService;
    }

    @GetMapping("/me")
    @Operation(
            summary = "내 세션 조회",
            description = "로그인한 사용자의 역할과 소속 기관을 반환합니다. 프론트 라우트 가드가 "
                    + "화면에 들어갈 때마다 호출합니다. signupCompleted 는 항상 포함되며 false 면 "
                    + "가입 미완료입니다(role 없음). role 은 org 또는 parent 이며, "
                    + "institutionId · institutionName(기관명) 은 role 이 org 일 때만 포함됩니다. "
                    + "termsAgreed 도 항상 포함되며 자기 역할의 서비스 약관(보호자 · 기관 따로)에 동의했는지입니다. "
                    + "가입 미완료는 false 입니다. withdrawal 도 항상 포함되며 none 이면 진행 중인 탈퇴가 없고, "
                    + "in_progress 면 탈퇴를 처리하는 중입니다(이때 다른 보호 API 는 409 WITHDRAWAL_IN_PROGRESS). "
                    + "탈퇴를 마친 회원의 출입증이면 401 ACCOUNT_WITHDRAWN 입니다. withdrawalEnabled 는 서버가 탈퇴를 열었는지이며 "
                    + "false 면 화면이 탈퇴 버튼을 숨깁니다."
    )
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "비로그인 · 로그인 만료 · 로그아웃(UNAUTHORIZED), 없는 회원(SESSION_USER_NOT_FOUND), "
                            + "탈퇴를 마친 회원(ACCOUNT_WITHDRAWN)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResponse<CurrentUserResponse> me(@AuthenticationPrincipal String userId) {
        return ApiResponse.success(
                userService.getCurrentUser(userId).withWithdrawalEnabled(withdrawalService.isEnabled()));
    }

    @PostMapping("/signup")
    @Operation(
            summary = "회원가입",
            description = "카카오 로그인만 한 사용자의 역할을 확정합니다. 보호자는 role 만, 기관은 "
                    + "organizationName·organizationType·businessNumber(숫자 10자리, 진위 검증 없음)를 함께 "
                    + "보냅니다. 기관이면 기관을 새로 만들고 소속시킵니다. 응답은 /auth/me 의 가입 완료 응답과 "
                    + "같습니다(막 가입하면 보호자 · 기관 모두 termsAgreed 가 false). 요청 전에 받은 XSRF-TOKEN "
                    + "쿠키 값을 X-XSRF-TOKEN 헤더에 포함해야 합니다."
    )
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "가입 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "role 누락·잘못된 값, 기관인데 기관 필드 누락, 보호자인데 기관 필드 포함, "
                            + "사업자등록번호 형식 오류 (INVALID_REQUEST)",
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
                .body(ApiResponse.success(userService.completeSignup(userId, request)
                        .withWithdrawalEnabled(withdrawalService.isEnabled())));
    }

    @PostMapping("/terms")
    @Operation(
            summary = "서비스 약관 동의",
            description = "회원이 동의한 약관 키와, 동의 화면이 읽은 약관의 판(versions)을 보냅니다. 보호자는 tos · privacy, "
                    + "기관 담당자는 org_tos · org_privacy · org_child_data 가 모두 필수이고 다른 역할의 약관은 섞을 수 없습니다. "
                    + "versions 가 지금 판(GET /api/v1/terms)과 다르면 아무것도 기록하지 않고 409 입니다. "
                    + "versions 를 보내지 않으면 본문을 보지 않은 동의로 보고 본문 없던 판으로 기록합니다(곧 필수). "
                    + "같은 판에 이미 동의했으면 아무것도 바꾸지 않고 같은 응답을 돌려줍니다. 동의하기 전의 회원은 "
                    + "/auth/me · /auth/terms · /auth/logout 외의 API 가 403 TERMS_NOT_AGREED 로 막힙니다. "
                    + "요청 전에 받은 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더에 포함해야 합니다."
    )
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "동의 저장(또는 이미 동의함)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "agreed 누락·빈 목록, 모르는 약관 키, 다른 역할의 약관이 섞임, 필수 약관 누락, "
                            + "versions 의 키가 agreed 와 다름 (INVALID_REQUEST)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "비로그인이거나 출입증이 더 이상 유효한 사용자를 가리키지 않음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "가입 미완료 (SIGNUP_NOT_COMPLETED), CSRF 토큰 누락 또는 불일치 (FORBIDDEN)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "versions 의 판이 지금 판과 다름 — 화면을 연 뒤 약관이 바뀌었다 (TERMS_VERSION_CHANGED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResponse<TermsAgreementResponse> agreeTerms(
            @AuthenticationPrincipal String userId, @Valid @RequestBody AgreeTermsRequest request) {
        return ApiResponse.success(userService.agreeTerms(userId, request.agreed(), request.versions()));
    }

    /**
     * 본문 없는 204 라 {@code ApiResponse} 로 감싸지 않는다
     * (backend/AGENTS.md, docs/api/api-conventions.md).
     */
    @PostMapping("/logout")
    @Operation(
            summary = "로그아웃",
            description = "access_token 쿠키를 만료시키고, 그 출입증의 로그인을 서버에 로그아웃으로 남깁니다. "
                    + "그 로그인의 출입증(연장된 것 포함)은 이후 인증되지 않습니다. 같은 회원의 다른 로그인은 그대로입니다. "
                    + "서명이 유효하고 최대 로그인 시간이 남아 있으면 만료된 출입증으로도 그 로그인을 무효로 합니다. "
                    + "로그인 번호가 없는 옛 출입증은 쿠키만 지웁니다. 요청 전에 받은 XSRF-TOKEN 쿠키 값을 "
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
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        // 출입증 필터는 로그아웃에서 출입증을 보지 않는다 — 여기서 직접 읽는다. 만료 직전 발급한 연장 쿠키도 막아야
        // 하므로, 서명이 맞고 최대 로그인 시간이 남아 있으면 만료된 출입증으로도 로그인 번호를 기록한다
        return endLogin(request);
    }

    /**
     * 회원 탈퇴. 접수를 커밋한 뒤 탈퇴 실행기를 깨우고 잠깐 기다린다. 완료(204)는 DB 에서 {@code deleted_at} 을 확인한 경우만이다.
     * 쿠키는 지우지 않고 로그아웃으로 기록하지도 않는다 — 늦게 온 응답이 다른 탭의 새 로그인 쿠키를 지울 수 있고, 로그아웃으로 기록하면
     * 204 를 놓친 화면이 같은 쿠키로 {@code /auth/me} 를 물을 때 탈퇴 완료(401 ACCOUNT_WITHDRAWN) 대신 로그인 풀림을 본다. 탈퇴한
     * 계정의 출입증은 서버가 늘 401 ACCOUNT_WITHDRAWN 으로 막는다. 202 는 본문이 있어 {@code ApiResponse} 로 감싼다.
     */
    @DeleteMapping("/me")
    @Operation(
            summary = "회원 탈퇴",
            description = "보호자와 가입을 마치지 않은 사람만 탈퇴할 수 있습니다(가입 · 약관 동의 전에도 부를 수 있음). "
                    + "카카오 연결을 끊고, 연결된 아이와의 연결 · 내 공유 동의 · 인사이트 열람 권한 · 기다리던 기관 연결 요청을 "
                    + "거둔 뒤, 카카오 회원번호를 지우고 탈퇴 표시를 합니다. 같은 기관에 다른 보호자의 동의가 남아 있으면 "
                    + "그 기관 공유는 계속됩니다. 같은 카카오 계정으로 다시 로그인하면 새 회원으로 가입부터 합니다. "
                    + "몇 초 안에 끝나면 204(쿠키는 지우지 않음 — 탈퇴한 계정의 출입증은 늘 401 ACCOUNT_WITHDRAWN 이라, 204 를 "
                    + "놓쳐도 같은 쿠키로 /auth/me 를 부르면 완료를 알 수 있음), "
                    + "아니면 202 로 접수만 알립니다 — 이때는 "
                    + "/auth/me 의 withdrawal 로 진행을 보고, 끝나면 /auth/me 가 401 ACCOUNT_WITHDRAWN 입니다. "
                    + "userId 에는 확인 화면이 보여 준 계정의 userId(/auth/me)를 넣습니다 — 지금 로그인한 계정과 다르면 409 입니다. "
                    + "서버가 탈퇴를 열지 않았으면(withdrawalEnabled false) 404 입니다. "
                    + "요청 전에 받은 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더에 포함해야 합니다."
    )
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "204", description = "탈퇴 완료. 본문 없음. 쿠키는 지우지 않음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "userId 누락 (INVALID_REQUEST)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "탈퇴를 아직 열지 않음 (WITHDRAWAL_NOT_AVAILABLE)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "202", description = "접수했고 처리 중 (withdrawal = in_progress)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "비로그인이거나 출입증이 더 이상 유효한 사용자를 가리키지 않음, 이미 탈퇴 (ACCOUNT_WITHDRAWN)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "기관 담당자 (ORGANIZATION_WITHDRAWAL_NOT_SUPPORTED) 또는 CSRF 토큰 누락 · 불일치 (FORBIDDEN)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "이미 탈퇴를 처리하는 중 (WITHDRAWAL_IN_PROGRESS) 또는 확인 화면의 계정과 로그인한 계정이 다름 "
                            + "(WITHDRAWAL_ACCOUNT_MISMATCH)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "502",
                    description = "카카오 연결 해제 첫 호출이 실패해 탈퇴를 취소함. 회원은 그대로 (KAKAO_UNLINK_FAILED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> withdraw(
            @AuthenticationPrincipal String userId,
            @RequestParam("userId") String expectedUserId) {
        WithdrawalSteps.Outcome outcome = withdrawalService.withdraw(userId, expectedUserId);
        return switch (outcome) {
            // 쿠키는 지우지 않는다. 기다리는 사이 다른 탭에서 로그인했으면 늦게 온 이 응답이 그 사람의 새 쿠키를 지운다.
            // 로그아웃으로 기록하지도 않는다 — 204 를 놓친 화면 · 같은 로그인의 다른 탭이 이 쿠키로 /auth/me 를 물으면
            // 401 ACCOUNT_WITHDRAWN 으로 완료를 알아야 한다. 탈퇴한 계정의 출입증은 서버가 늘 이렇게 막고, 다음 로그인이 쿠키를 덮어쓴다
            case COMPLETED -> ResponseEntity.noContent().build();
            case IN_PROGRESS -> ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(ApiResponse.success(WithdrawalResponse.inProgress()));
            case CANCELED -> throw new UserException(UserErrorCode.KAKAO_UNLINK_FAILED);
        };
    }

    /** 이 요청의 출입증이 가리키는 로그인을 로그아웃으로 남긴다. */
    private void revokeLoginOf(HttpServletRequest request) {
        jwtProvider.parseForLogout(jwtCookie.read(request))
                .filter(token -> token.sessionId() != null)
                .ifPresent(this::revoke);
    }

    /** 이 로그인을 로그아웃으로 남기고 출입증 쿠키(연장 쿠키 포함)를 지운 204. */
    private ResponseEntity<Void> endLogin(HttpServletRequest request) {
        revokeLoginOf(request);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, jwtCookie.expire().toString())
                .headers(headers -> jwtCookie.expireRenewals(request).forEach(cookie ->
                        headers.add(HttpHeaders.SET_COOKIE, cookie.toString())))
                .build();
    }

    /** 기록에 실패해도 쿠키는 지운다. 로그아웃이 듣지 않으면 공용 PC 의 다음 사람이 그대로 들어온다. */
    private void revoke(AccessToken token) {
        try {
            revokedSessionService.revoke(token.sessionId(), Long.valueOf(token.subject()));
        } catch (RuntimeException e) {
            log.error("로그아웃 기록에 실패했습니다. 쿠키는 지웁니다: userId={}", token.subject(), e);
        }
    }
}
