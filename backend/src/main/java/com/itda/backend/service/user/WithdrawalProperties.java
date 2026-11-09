package com.itda.backend.service.user;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.withdrawal.*} 설정 — 회원 탈퇴.
 *
 * @param waitForCompletion 탈퇴 API 가 실행기 결과를 기다리는 시간. 그 안에 끝나면 204, 아니면 202 다
 * @param lock              실행기 잠금. {@code auto}(기본, DB 를 보고 고름) · {@code advisory}(PostgreSQL 세션 잠금) ·
 *                          {@code local}(한 프로세스 안, H2 전용 — advisory lock 함수가 없다). {@link WithdrawalLocksConfig}
 * @param kakao             카카오 연결 해제 호출
 * @param worker            진행 중인 탈퇴를 다시 보는 주기 작업
 */
@ConfigurationProperties(prefix = "app.withdrawal")
public record WithdrawalProperties(Duration waitForCompletion, String lock, Kakao kakao, Worker worker) {

    /**
     * @param baseUrl        카카오 API 주소
     * @param adminKey       서비스 앱의 <b>대표</b> 어드민 키. 연결 해제 호출과 연결 해제 웹훅 검증에 함께 쓴다. 비어 있으면 카카오
     *                       계정 회원의 탈퇴는 첫 호출 실패로 취소되고, 웹훅은 모두 401 이다
     * @param appId          카카오 앱 ID(숫자). 있으면 연결 해제 웹훅의 {@code app_id} 가 같은지도 본다
     * @param connectTimeout 연결 대기
     * @param readTimeout    응답 대기. 짧게 둔다 — 넘으면 결과를 모르는 것으로 보고 실행기가 다시 부른다
     */
    public record Kakao(String baseUrl, String adminKey, String appId, Duration connectTimeout, Duration readTimeout) {
    }

    /**
     * @param enabled 주기 작업을 켤지. 꺼도 탈퇴 API 가 실행기를 바로 깨우므로 접수한 탈퇴는 한 번 처리된다
     * @param delayMs 주기
     */
    public record Worker(boolean enabled, long delayMs) {
    }
}
