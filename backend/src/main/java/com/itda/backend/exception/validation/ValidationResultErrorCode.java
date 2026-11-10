package com.itda.backend.exception.validation;

import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;

import com.itda.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ValidationResultErrorCode implements ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "VALIDATION_RESULT_INVALID_REQUEST", "요청이 올바르지 않습니다."),
    OUTDATED(HttpStatus.BAD_REQUEST, "VALIDATION_RESULT_OUTDATED", "더 최근 판정이 있습니다. 목록을 새로 불러와 주세요."),
    CONTENT_UNCHANGED(HttpStatus.BAD_REQUEST, "VALIDATION_RESULT_CONTENT_UNCHANGED", "본문이 고쳐지지 않았습니다."),
    CHILD_NOT_SHARED(HttpStatus.BAD_REQUEST, "VALIDATION_RESULT_CHILD_NOT_SHARED", "공유가 끝난 아이의 기록은 다시 검증할 수 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "VALIDATION_RESULT_NOT_FOUND", "검증 결과를 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    @Override
    public Level logLevel() {
        return Level.INFO;
    }
}
