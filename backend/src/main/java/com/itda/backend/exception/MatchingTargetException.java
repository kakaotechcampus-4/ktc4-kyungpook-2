package com.itda.backend.exception;

/** 매칭 요청을 만들 수 없는 일지다 (원본 파일이 삭제됐거나 원본의 기관을 찾을 수 없음). 워커가 FAILED 로 기록한다. */
public class MatchingTargetException extends RuntimeException {

    public MatchingTargetException(String message) {
        super(message);
    }
}
