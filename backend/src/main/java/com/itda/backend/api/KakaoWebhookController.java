package com.itda.backend.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.itda.backend.global.exception.ErrorResponse;
import com.itda.backend.global.security.ApiPaths;
import com.itda.backend.service.user.KakaoUnlinkWebhookService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 카카오가 부르는 웹훅. 로그인 쿠키 · CSRF 토큰 없이 들어온다 — 대신 어드민 키 헤더로 카카오가 보낸 것인지 본다.
 * 본문 없는 200 이라 {@code ApiResponse} 로 감싸지 않는다.
 */
@RestController
@Tag(name = "카카오 웹훅", description = "카카오가 서비스로 보내는 알림")
public class KakaoWebhookController {

    private final KakaoUnlinkWebhookService webhookService;

    public KakaoWebhookController(KakaoUnlinkWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    /** 카카오는 GET(쿼리)과 POST(폼) 둘 다로 보낼 수 있다. 파라미터는 두 경우 모두 {@code @RequestParam} 으로 읽힌다. */
    @RequestMapping(path = ApiPaths.KAKAO_UNLINK_WEBHOOK, method = {RequestMethod.GET, RequestMethod.POST})
    @Operation(
            summary = "카카오 연결 해제 웹훅",
            description = "사용자가 카카오 쪽(연결된 서비스 관리 · 카카오계정 탈퇴 · 고객센터 등)에서 먼저 앱 연결을 끊으면 카카오가 "
                    + "부릅니다. Authorization: KakaoAK {대표 어드민 키} 가 맞아야 합니다. 보호자 · 가입 전 회원은 탈퇴를 접수해 "
                    + "카카오를 다시 부르지 않고 아이 연결 정리 · 식별정보 삭제로 넘기고, 기관 담당자는 계정을 남깁니다. "
                    + "카카오 규칙대로 회원이 없거나 처리에 실패해도 200 입니다(카카오는 다시 보내지 않습니다). "
                    + "3초 응답 기한을 지키려고 받은 웹훅은 수신함에 남기고 바로 답하며, 회원 처리는 그 뒤 따로 합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "받음. 본문 없음"),
            @ApiResponse(responseCode = "401", description = "어드민 키가 맞지 않음 (KAKAO_WEBHOOK_UNAUTHORIZED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> unlink(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(value = "app_id", required = false) String appId,
            @RequestParam(value = "user_id", required = false) String userId,
            @RequestParam(value = "referrer_type", required = false) String referrerType) {
        webhookService.handle(authorization, appId, userId, referrerType);
        return ResponseEntity.ok().build();
    }
}
