package com.itda.backend.exception.organization;

import org.springframework.http.HttpStatus;

import com.itda.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OrganizationErrorCode implements ErrorCode {

    DUPLICATE_BUSINESS_NUMBER(HttpStatus.CONFLICT, "DUPLICATE_BUSINESS_NUMBER", "이미 등록된 사업자등록번호입니다."),

    INSTITUTION_CODE_ISSUE_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "INSTITUTION_CODE_ISSUE_FAILED",
            "기관 코드를 만들지 못했습니다. 잠시 후 다시 시도해주세요."),

    INVALID_HANDOVER_CODE(HttpStatus.BAD_REQUEST, "INVALID_HANDOVER_CODE",
            "인계 코드가 맞지 않거나 시간이 지났습니다. 이전 담당자에게 새 코드를 받아 주세요."),

    HANDOVER_CODE_ISSUE_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "HANDOVER_CODE_ISSUE_FAILED",
            "인계 코드를 만들지 못했습니다. 잠시 후 다시 시도해주세요.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
