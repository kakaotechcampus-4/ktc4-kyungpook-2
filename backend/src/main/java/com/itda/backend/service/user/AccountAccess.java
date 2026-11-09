package com.itda.backend.service.user;

/**
 * 로그인한 회원이 보호 API 를 쓸 수 있는지. 가입 미완료 차단 필터가 이 값으로 막는다.
 *
 * <p>가입이 약관보다 먼저다 — 가입하지 않은 회원은 보호자인지조차 모른다.
 */
public enum AccountAccess {
    /** 탈퇴를 처리하고 있다 → 409 WITHDRAWAL_IN_PROGRESS. 가입 · 약관보다 먼저 본다 */
    WITHDRAWAL_IN_PROGRESS,
    /** 역할을 고르지 않았다 → 403 SIGNUP_NOT_COMPLETED */
    SIGNUP_NOT_COMPLETED,
    /** 보호자인데 서비스 약관에 동의하지 않았다 → 403 TERMS_NOT_AGREED */
    TERMS_NOT_AGREED,
    OK
}
