package com.itda.backend.exception;

/** "지금 요약" 을 받을 수 없다 (그 아이·날짜에 요약할 새 일지가 없음). */
public class SummaryRunValidationException extends RuntimeException {

    public SummaryRunValidationException(String message) {
        super(message);
    }
}
