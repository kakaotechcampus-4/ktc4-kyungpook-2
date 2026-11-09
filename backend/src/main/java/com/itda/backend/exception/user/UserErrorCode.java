package com.itda.backend.exception.user;

import org.springframework.http.HttpStatus;

import com.itda.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * docs/api/api-conventions.md — 사용자 도메인 오류 코드.
 */
@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {

    /**
     * 토큰은 유효한데 그 사용자가 DB 에 없다. 404 가 아니라 401 이다 —
     * 자격 증명이 더 이상 누구도 가리키지 못하는 상태이므로 해야 할 일은 "다시 로그인" 이고,
     * 프론트도 401 을 "역할 없음 → 로그인 화면" 으로 해석한다.
     *
     * <p>규약 표의 USER_NOT_FOUND(404) 와 상태가 달라 혼동되지 않도록 코드명을 따로 둔다.
     */
    SESSION_USER_NOT_FOUND(HttpStatus.UNAUTHORIZED, "SESSION_USER_NOT_FOUND", "인증 정보를 확인할 수 없습니다."),

    /**
     * 출입증은 유효한데(서명 · 만료 · 로그아웃 통과) 그 회원이 탈퇴를 마쳤다. 탈퇴 안내 화면은 이 코드일 때만
     * "탈퇴가 끝났다" 고 보여 준다 — 로그인 만료 · 쿠키 없음 · 로그아웃은 다른 401 이다.
     */
    ACCOUNT_WITHDRAWN(HttpStatus.UNAUTHORIZED, "ACCOUNT_WITHDRAWN", "탈퇴한 회원입니다."),

    /** 탈퇴를 처리하는 중이다. 세션 조회(/auth/me) · 로그아웃 외에는 막힌다. 탈퇴를 다시 신청해도 이 코드다. */
    WITHDRAWAL_IN_PROGRESS(HttpStatus.CONFLICT, "WITHDRAWAL_IN_PROGRESS", "탈퇴를 처리하고 있습니다."),

    /** 탈퇴를 아직 열지 않았다(app.withdrawal.enabled 꺼짐). 운영 출시 조건이 갖춰지기 전이다. */
    WITHDRAWAL_NOT_AVAILABLE(HttpStatus.NOT_FOUND, "WITHDRAWAL_NOT_AVAILABLE", "지금은 탈퇴할 수 없습니다."),

    /**
     * 탈퇴 확인 화면이 보여 준 계정과 지금 로그인한 계정이 다르다. 다른 탭에서 다른 계정으로 로그인한 경우다 — 사용자가 확인하지
     * 않은 계정을 지우지 않는다. 화면을 새로 열어 다시 확인하게 한다.
     */
    WITHDRAWAL_ACCOUNT_MISMATCH(HttpStatus.CONFLICT, "WITHDRAWAL_ACCOUNT_MISMATCH",
            "로그인한 계정이 바뀌었습니다. 화면을 새로 열어 다시 확인해주세요."),

    /** 기관 담당자 탈퇴는 아직 지원하지 않는다. 카카오를 부르기 전에 막는다. */
    ORGANIZATION_WITHDRAWAL_NOT_SUPPORTED(HttpStatus.FORBIDDEN, "ORGANIZATION_WITHDRAWAL_NOT_SUPPORTED",
            "기관 담당자는 탈퇴할 수 없습니다."),

    /** 카카오 연결 해제의 첫 호출이 분명히 실패해 탈퇴 접수를 취소했다. 회원은 그대로다. */
    KAKAO_UNLINK_FAILED(HttpStatus.BAD_GATEWAY, "KAKAO_UNLINK_FAILED",
            "카카오 연결을 끊지 못해 탈퇴하지 못했습니다. 잠시 후 다시 시도해주세요."),

    /** 로그인은 됐지만 기관 소속이 아니다. 기관 전용 API 에서 막힌다. */
    ORGANIZATION_NOT_ASSIGNED(HttpStatus.FORBIDDEN, "ORGANIZATION_NOT_ASSIGNED", "기관 소속 사용자만 이용할 수 있습니다."),

    /** 로그인은 됐지만 보호자가 아니다. 보호자 전용 API(초대 코드 수락 등)에서 막힌다. */
    PARENT_REQUIRED(HttpStatus.FORBIDDEN, "PARENT_REQUIRED", "보호자만 이용할 수 있습니다."),

    /** 카카오 로그인만 하고 가입(역할 선택)을 마치지 않았다. /auth/me · /auth/signup · /auth/logout 외에는 막힌다. */
    SIGNUP_NOT_COMPLETED(HttpStatus.FORBIDDEN, "SIGNUP_NOT_COMPLETED", "회원가입을 먼저 완료해주세요."),

    /** 회원이 자기 역할의 서비스 약관(지금 판)에 동의하지 않았다. /auth/me · /auth/terms · /auth/logout 외에는 막힌다. */
    TERMS_NOT_AGREED(HttpStatus.FORBIDDEN, "TERMS_NOT_AGREED", "서비스 약관에 먼저 동의해주세요."),

    /**
     * 약관 동의 요청에 다른 역할의 약관이 섞였거나 필수 약관이 빠졌다. 응답 코드는 공통 INVALID_REQUEST 와 같다.
     */
    INVALID_TERMS_AGREEMENT(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "잘못된 요청입니다."),

    /**
     * 동의 화면이 읽은 약관 판이 지금 판과 다르다 — 화면을 연 뒤 약관이 바뀌었다. 아무것도 기록하지 않는다.
     * 화면은 약관을 다시 받아 바뀐 약관을 다시 확인하게 한다.
     */
    TERMS_VERSION_CHANGED(HttpStatus.CONFLICT, "TERMS_VERSION_CHANGED", "약관이 바뀌었습니다. 바뀐 약관을 확인하고 다시 동의해주세요."),

    /** 이미 역할이 정해진 회원의 가입 재요청. 역할은 한 번 정해지면 바뀌지 않는다. */
    ALREADY_SIGNED_UP(HttpStatus.CONFLICT, "ALREADY_SIGNED_UP", "이미 회원가입을 완료했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
