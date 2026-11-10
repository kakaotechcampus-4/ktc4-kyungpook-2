package com.itda.backend.exception.checklist;

import org.springframework.http.HttpStatus;

import com.itda.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ChecklistErrorCode implements ErrorCode {

    CHECKLIST_NOT_FOUND(HttpStatus.NOT_FOUND, "CHECKLIST_NOT_FOUND", "준비물·확인사항을 찾을 수 없습니다."),

    INVALID_CHECKLIST_TARGET(HttpStatus.BAD_REQUEST, "INVALID_CHECKLIST_TARGET", "명부에 없는 아이가 있습니다."),

    INVALID_CHECKLIST_CURSOR(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "잘못된 요청입니다."),

    INVALID_CHECKLIST_STATUS(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "잘못된 요청입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
