package com.itda.backend.exception;

/** 검증 요청을 만들 수 없는 일지다 (일지가 삭제됐거나 확정된 아동이 없음). 워커가 FAILED 로 기록한다. */
public class ValidationTargetException extends RuntimeException {

    public ValidationTargetException(String message) {
        super(message);
    }
}
