package com.itda.backend.service.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.stereotype.Service;

import com.itda.backend.exception.auth.AuthErrorCode;
import com.itda.backend.exception.auth.AuthException;

import lombok.extern.slf4j.Slf4j;

/**
 * 카카오 연결 해제 웹훅. 사용자가 카카오 쪽(연결된 서비스 관리 · 카카오계정 탈퇴 · 고객센터 등)에서 먼저 앱 연결을 끊으면 카카오가
 * 알려 준다. 우리가 연결 해제 API 로 끊은 경우에는 오지 않는다.
 *
 * <p>카카오 규칙: 헤더 {@code Authorization: KakaoAK ${대표 어드민 키}} 로 카카오가 보낸 것인지 본다. 3초 안에 {@code 200} 으로
 * 답해야 하고, 회원이 없거나 오류가 나도 {@code 200} 이다. 다시 보내 주지 않는다 — 그래서 여기서는 접수만 하고 정리는 탈퇴
 * 실행기에 넘긴다. 접수가 실패하면 그 이벤트는 잃으므로 오류 로그를 남긴다.
 *
 * <p>받는 즉시 탈퇴로 넘기지 않는다. 웹훅에는 해제 시각이 없어, 옛 연결의 늦은 웹훅이 같은 카카오 계정으로 다시 가입한 새 계정에
 * 닿을 수 있다. 알림만 남기고, 실행기가 카카오에 지금 연결 상태를 물어 정말 끊겨 있을 때만 탈퇴한다.
 */
@Slf4j
@Service
public class KakaoUnlinkWebhookService {

    private final WithdrawalSteps steps;
    private final WithdrawalExecutor executor;
    private final byte[] expectedAuthorization;
    private final String appId;
    private final boolean enabled;

    public KakaoUnlinkWebhookService(WithdrawalSteps steps, WithdrawalExecutor executor, WithdrawalProperties properties) {
        this.steps = steps;
        this.executor = executor;
        String adminKey = properties.kakao().adminKey();
        this.expectedAuthorization = adminKey == null || adminKey.isBlank()
                ? null
                : ("KakaoAK " + adminKey).getBytes(StandardCharsets.UTF_8);
        String configuredAppId = properties.kakao().appId();
        this.appId = configuredAppId == null || configuredAppId.isBlank() ? null : configuredAppId.trim();
        this.enabled = properties.enabled();
    }

    /**
     * @throws AuthException 어드민 키가 맞지 않으면 401. 카카오가 보낸 요청이 아니다
     */
    public void handle(String authorization, String requestAppId, String kakaoUserId, String referrerType) {
        verify(authorization);
        if (appId != null && !appId.equals(requestAppId)) {
            log.warn("다른 앱의 연결 해제 웹훅이라 무시합니다: appId={}", requestAppId);
            return;
        }
        if (!enabled) {
            // 탈퇴를 아직 열지 않았다(보관 근거 · 출시 조건 전). 카카오가 다시 보내지 않으므로 이 이벤트는 남지 않는다
            log.warn("탈퇴를 열지 않아 연결 해제 웹훅을 무시합니다: referrerType={}", referrerType);
            return;
        }
        if (kakaoUserId == null || kakaoUserId.isBlank() || !kakaoUserId.chars().allMatch(c -> c >= '0' && c <= '9')) {
            log.warn("회원번호가 없거나 숫자가 아닌 연결 해제 웹훅이라 무시합니다: referrerType={}", referrerType);
            return;
        }
        try {
            steps.reportUnlinkedByKakao(kakaoUserId).ifPresent(userId -> {
                log.info("연결 해제 웹훅을 받아 확인을 맡깁니다: userId={} referrerType={}", userId, referrerType);
                executor.submit(userId);
            });
        } catch (RuntimeException e) {
            // 카카오는 다시 보내지 않는다. 200 으로 답하되 잃은 이벤트를 찾을 수 있게 남긴다(회원번호는 남기지 않는다)
            log.error("연결 해제 웹훅을 처리하지 못했습니다: referrerType={}", referrerType, e);
        }
    }

    private void verify(String authorization) {
        if (expectedAuthorization == null) {
            log.error("카카오 어드민 키(app.withdrawal.kakao.admin-key)가 없어 연결 해제 웹훅을 검증할 수 없습니다.");
            throw new AuthException(AuthErrorCode.KAKAO_WEBHOOK_UNAUTHORIZED);
        }
        byte[] actual = authorization == null ? new byte[0] : authorization.trim().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedAuthorization, actual)) {
            throw new AuthException(AuthErrorCode.KAKAO_WEBHOOK_UNAUTHORIZED);
        }
    }
}
