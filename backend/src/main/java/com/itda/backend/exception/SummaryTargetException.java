package com.itda.backend.exception;

/** 요약 요청을 만들 수 없는 묶음이다 (묶음의 일지가 모두 삭제됨). 워커가 그 묶음을 실패로 기록한다. */
public class SummaryTargetException extends RuntimeException {

    public SummaryTargetException(String message) {
        super(message);
    }
}
