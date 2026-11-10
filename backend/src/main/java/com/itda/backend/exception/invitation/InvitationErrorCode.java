package com.itda.backend.exception.invitation;

import org.springframework.http.HttpStatus;

import com.itda.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum InvitationErrorCode implements ErrorCode {

    INVALID_INVITE_CODE(HttpStatus.BAD_REQUEST, "INVALID_INVITE_CODE", "초대 코드가 올바르지 않거나 만료되었어요."),

    LINK_ALREADY_EXISTS(HttpStatus.CONFLICT, "LINK_ALREADY_EXISTS", "이미 연결된 아이입니다."),

    INVITATION_NOT_FOUND(HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND", "초대 코드를 찾을 수 없습니다."),

    GUARDIAN_NOT_FOUND(HttpStatus.NOT_FOUND, "GUARDIAN_NOT_FOUND", "연결된 보호자를 찾을 수 없습니다."),

    CHILD_NOT_LINKED(HttpStatus.FORBIDDEN, "FORBIDDEN", "권한이 없습니다."),

    GUARDIAN_LINKED_BY_OTHER_ORGANIZATION(HttpStatus.FORBIDDEN, "GUARDIAN_LINKED_BY_OTHER_ORGANIZATION",
            "다른 기관을 통해 연결된 보호자는 해제할 수 없습니다."),

    INVITE_CODE_ISSUE_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "INVITE_CODE_ISSUE_FAILED",
            "초대 코드를 만들지 못했어요. 잠시 후 다시 시도해주세요.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
