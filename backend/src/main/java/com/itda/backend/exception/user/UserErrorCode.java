package com.itda.backend.exception.user;

import org.springframework.http.HttpStatus;

import com.itda.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {

    SESSION_USER_NOT_FOUND(HttpStatus.UNAUTHORIZED, "SESSION_USER_NOT_FOUND", "인증 정보를 확인할 수 없습니다."),

    ACCOUNT_WITHDRAWN(HttpStatus.UNAUTHORIZED, "ACCOUNT_WITHDRAWN", "탈퇴한 회원입니다."),

    WITHDRAWAL_IN_PROGRESS(HttpStatus.CONFLICT, "WITHDRAWAL_IN_PROGRESS", "탈퇴를 처리하고 있습니다."),

    WITHDRAWAL_NOT_AVAILABLE(HttpStatus.NOT_FOUND, "WITHDRAWAL_NOT_AVAILABLE", "지금은 탈퇴할 수 없습니다."),

    WITHDRAWAL_ACCOUNT_MISMATCH(HttpStatus.CONFLICT, "WITHDRAWAL_ACCOUNT_MISMATCH",
            "로그인한 계정이 바뀌었습니다. 화면을 새로 열어 다시 확인해주세요."),

    ORGANIZATION_WITHDRAWAL_NOT_SUPPORTED(HttpStatus.FORBIDDEN, "ORGANIZATION_WITHDRAWAL_NOT_SUPPORTED",
            "기관 담당자는 탈퇴할 수 없습니다."),

    KAKAO_UNLINK_FAILED(HttpStatus.BAD_GATEWAY, "KAKAO_UNLINK_FAILED",
            "카카오 연결을 끊지 못해 탈퇴하지 못했습니다. 잠시 후 다시 시도해주세요."),

    ORGANIZATION_NOT_ASSIGNED(HttpStatus.FORBIDDEN, "ORGANIZATION_NOT_ASSIGNED", "기관 소속 사용자만 이용할 수 있습니다."),

    PARENT_REQUIRED(HttpStatus.FORBIDDEN, "PARENT_REQUIRED", "보호자만 이용할 수 있습니다."),

    SIGNUP_NOT_COMPLETED(HttpStatus.FORBIDDEN, "SIGNUP_NOT_COMPLETED", "회원가입을 먼저 완료해주세요."),

    TERMS_NOT_AGREED(HttpStatus.FORBIDDEN, "TERMS_NOT_AGREED", "서비스 약관에 먼저 동의해주세요."),

    INVALID_TERMS_AGREEMENT(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "잘못된 요청입니다."),

    TERMS_VERSION_CHANGED(HttpStatus.CONFLICT, "TERMS_VERSION_CHANGED", "약관이 바뀌었습니다. 바뀐 약관을 확인하고 다시 동의해주세요."),

    ALREADY_SIGNED_UP(HttpStatus.CONFLICT, "ALREADY_SIGNED_UP", "이미 회원가입을 완료했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
