package com.itda.backend.exception.invitation;

import org.springframework.http.HttpStatus;

import com.itda.backend.global.exception.ErrorCode;

/**
 * docs/api/api-spec.md §6 — 초대 코드·보호자 연결 오류 코드.
 */
public enum InvitationErrorCode implements ErrorCode {

    /**
     * 형식이 틀렸거나, 없거나, 만료·사용·취소된 코드. 아이가 삭제됐거나 발급 기관 명부에서 빠진 경우도 같다.
     * 이유를 나누지 않는다 — 나누면 어떤 코드가 실제로 있었는지 알려 주게 된다.
     */
    INVALID_INVITE_CODE(HttpStatus.BAD_REQUEST, "INVALID_INVITE_CODE", "초대 코드가 올바르지 않거나 만료되었어요."),

    /** 이미 이 아이와 연결된 보호자. 코드는 쓰지 않은 채로 남는다. */
    LINK_ALREADY_EXISTS(HttpStatus.CONFLICT, "LINK_ALREADY_EXISTS", "이미 연결된 아이입니다."),

    /** 없거나, 다른 아이·기관의 것이거나, 이미 쓸 수 없는 초대. */
    INVITATION_NOT_FOUND(HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND", "초대 코드를 찾을 수 없습니다."),

    /** 이 아이와 연결돼 있지 않은 보호자. */
    GUARDIAN_NOT_FOUND(HttpStatus.NOT_FOUND, "GUARDIAN_NOT_FOUND", "연결된 보호자를 찾을 수 없습니다."),

    /**
     * 보호자가 자기와 연결되지 않은 아이를 다루려 했다. api-spec §5 의 보호자 API 규칙대로 공통 FORBIDDEN 코드로 답한다.
     */
    CHILD_NOT_LINKED(HttpStatus.FORBIDDEN, "FORBIDDEN", "권한이 없습니다."),

    /** 다른 기관이 발급한 코드로 연결된 보호자. 기관은 자기 코드로 생긴 연결만 끊을 수 있다. */
    GUARDIAN_LINKED_BY_OTHER_ORGANIZATION(HttpStatus.FORBIDDEN, "GUARDIAN_LINKED_BY_OTHER_ORGANIZATION",
            "다른 기관을 통해 연결된 보호자는 해제할 수 없습니다."),

    /**
     * 새 코드가 동시에 발급된 다른 코드와 겹쳤다. 트랜잭션이 이미 깨져 그 안에서 다시 뽑을 수 없으므로
     * 요청 전체를 실패시키고 클라이언트가 다시 보낸다. 31⁸ 가지라 실제로는 거의 일어나지 않는다.
     */
    INVITE_CODE_ISSUE_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "INVITE_CODE_ISSUE_FAILED",
            "초대 코드를 만들지 못했어요. 잠시 후 다시 시도해주세요.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    InvitationErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    @Override
    public HttpStatus getStatus() {
        return status;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
