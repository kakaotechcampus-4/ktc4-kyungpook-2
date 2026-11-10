package com.itda.backend.exception.child;

import org.springframework.http.HttpStatus;

import com.itda.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum InstitutionLinkErrorCode implements ErrorCode {

    INSTITUTION_CODE_NOT_FOUND(HttpStatus.NOT_FOUND, "INSTITUTION_CODE_NOT_FOUND", "일치하는 기관을 찾지 못했습니다."),

    INSTITUTION_ALREADY_LINKED(HttpStatus.CONFLICT, "INSTITUTION_ALREADY_LINKED", "이미 연결된 기관입니다."),

    LINK_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "LINK_REQUEST_NOT_FOUND", "연결 요청을 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
