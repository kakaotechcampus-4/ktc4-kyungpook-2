package com.itda.backend.service.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.stereotype.Service;

import com.itda.backend.exception.auth.AuthErrorCode;
import com.itda.backend.exception.auth.AuthException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class KakaoUnlinkWebhookService {

    private final KakaoUnlinkInbox inbox;
    private final byte[] expectedAuthorization;
    private final String appId;
    private final boolean enabled;

    public KakaoUnlinkWebhookService(KakaoUnlinkInbox inbox, WithdrawalProperties properties) {
        this.inbox = inbox;
        String adminKey = properties.kakao().adminKey();
        this.expectedAuthorization = adminKey == null || adminKey.isBlank()
                ? null
                : ("KakaoAK " + adminKey).getBytes(StandardCharsets.UTF_8);
        String configuredAppId = properties.kakao().appId();
        this.appId = configuredAppId == null || configuredAppId.isBlank() ? null : configuredAppId.trim();
        this.enabled = properties.enabled();
    }

    public void handle(String authorization, String requestAppId, String kakaoUserId, String referrerType) {
        verify(authorization);
        if (appId != null && !appId.equals(requestAppId)) {
            log.warn("다른 앱의 연결 해제 웹훅이라 무시합니다: appId={}", requestAppId);
            return;
        }
        if (!enabled) {
            log.warn("탈퇴를 열지 않아 연결 해제 웹훅을 무시합니다: referrerType={}", referrerType);
            return;
        }
        if (kakaoUserId == null || kakaoUserId.isBlank() || !kakaoUserId.chars().allMatch(c -> c >= '0' && c <= '9')) {
            log.warn("회원번호가 없거나 숫자가 아닌 연결 해제 웹훅이라 무시합니다: referrerType={}", referrerType);
            return;
        }
        if (inbox.receive(kakaoUserId)) {
            log.info("연결 해제 웹훅을 받아 수신함에 남겼습니다: referrerType={}", referrerType);
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
