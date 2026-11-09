package com.itda.backend.exception.organization;

import org.springframework.http.HttpStatus;

import com.itda.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * docs/api/api-conventions.md — 기관 도메인 오류 코드.
 */
@Getter
@RequiredArgsConstructor
public enum OrganizationErrorCode implements ErrorCode {

    /** 기관 1곳당 계정 1개. 이미 가입된 사업자등록번호로 다시 가입하려 했다. */
    DUPLICATE_BUSINESS_NUMBER(HttpStatus.CONFLICT, "DUPLICATE_BUSINESS_NUMBER", "이미 등록된 사업자등록번호입니다."),

    /**
     * 기관 코드를 겹치지 않게 뽑지 못했다. 다른 요청이 같은 코드를 먼저 저장해 유니크 제약에 걸리면 트랜잭션이
     * 이미 깨져 다시 저장할 수 없으므로 요청 전체를 끝낸다. 클라이언트가 다시 보내면 된다.
     */
    INSTITUTION_CODE_ISSUE_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "INSTITUTION_CODE_ISSUE_FAILED",
            "기관 코드를 만들지 못했습니다. 잠시 후 다시 시도해주세요."),

    /**
     * 인계 코드로 가입하려 했는데 쓸 수 없는 코드다 (#478). 형식 오류 · 없음 · 만료 · 이미 씀 · 취소됨 · 발급한 담당자가 이미
     * 탈퇴함을 나누지 않는다 — 나누면 어떤 코드가 있는지 알려 주게 된다 (초대 코드 INVALID_INVITE_CODE 와 같다).
     */
    INVALID_HANDOVER_CODE(HttpStatus.BAD_REQUEST, "INVALID_HANDOVER_CODE",
            "인계 코드가 맞지 않거나 시간이 지났습니다. 이전 담당자에게 새 코드를 받아 주세요."),

    /** 인계 코드를 겹치지 않게 뽑지 못했다. 다시 보내면 된다. */
    HANDOVER_CODE_ISSUE_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "HANDOVER_CODE_ISSUE_FAILED",
            "인계 코드를 만들지 못했습니다. 잠시 후 다시 시도해주세요.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
