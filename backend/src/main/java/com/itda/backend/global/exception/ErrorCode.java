package com.itda.backend.global.exception;

import org.springframework.http.HttpStatus;

/**
 * docs/api/api-conventions.md — 공통 오류는 CommonErrorCode, 도메인 오류는
 * 도메인별 enum(예: AuthErrorCode)으로 분리한다. 두 종류 모두 이 계약을 구현한다.
 */
public interface ErrorCode {

    HttpStatus getStatus();

    String getCode();

    String getMessage();
}
