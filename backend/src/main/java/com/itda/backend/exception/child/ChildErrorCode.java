package com.itda.backend.exception.child;

import org.springframework.http.HttpStatus;

import com.itda.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ChildErrorCode implements ErrorCode {

    DUPLICATE_EXTERNAL_ID(HttpStatus.CONFLICT, "DUPLICATE_EXTERNAL_ID", "이미 사용 중인 관리번호입니다."),

    DUPLICATE_CHILD(HttpStatus.CONFLICT, "DUPLICATE_CHILD", "같은 이름·생년월일의 아이가 이미 등록되어 있습니다."),

    CHILD_NOT_FOUND(HttpStatus.NOT_FOUND, "CHILD_NOT_FOUND", "아동을 찾을 수 없습니다."),

    CHILD_NOT_LINKED(HttpStatus.FORBIDDEN, "FORBIDDEN", "권한이 없습니다."),

    JOURNAL_NOT_FOUND(HttpStatus.NOT_FOUND, "JOURNAL_NOT_FOUND", "일지를 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
