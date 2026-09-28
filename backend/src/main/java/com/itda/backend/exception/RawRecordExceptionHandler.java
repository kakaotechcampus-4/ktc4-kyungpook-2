package com.itda.backend.exception;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.itda.backend.global.exception.ErrorResponse;

import lombok.extern.slf4j.Slf4j;

// RawRecord 전용 예외만 처리한다. Spring MVC 표준 요청 오류와 미분류 예외(catch-all)는
// global.exception.GlobalExceptionHandler가 처리한다.
// @Order: 여러 @RestControllerAdvice가 있을 때 어느 게 먼저 매칭될지가 빈 등록 순서에
// 암묵적으로 의존하지 않도록 명시 고정한다 — GlobalExceptionHandler의 catch-all이 먼저
// 매칭되면 여기 예외들이 의도한 4xx 대신 500으로 새어나갈 수 있다(최재혁님과 PR #35에서 논의,
// 박찬진님도 feat/be/#34에서 독립적으로 같은 결론). Ambiguous 오류(같은 advice 클래스 안에서
// 중복 선언할 때만 발생)와는 별개 문제다.
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RawRecordExceptionHandler {

    @ExceptionHandler(RawRecordValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(RawRecordValidationException e) {
        return ResponseEntity.status(RawRecordErrorCode.INVALID_REQUEST.getStatus())
                .body(ErrorResponse.of(RawRecordErrorCode.INVALID_REQUEST));
    }

    @ExceptionHandler(RawRecordNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(RawRecordNotFoundException e) {
        return ResponseEntity.status(RawRecordErrorCode.NOT_FOUND.getStatus())
                .body(ErrorResponse.of(RawRecordErrorCode.NOT_FOUND));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(RawRecordErrorCode.FILE_TOO_LARGE.getStatus())
                .body(ErrorResponse.of(RawRecordErrorCode.FILE_TOO_LARGE));
    }

    @ExceptionHandler(RawRecordStorageException.class)
    public ResponseEntity<ErrorResponse> handleStorageFailure(RawRecordStorageException e) {
        log.error("raw record storage failure", e);
        return ResponseEntity.status(RawRecordErrorCode.STORAGE_FAILED.getStatus())
                .body(ErrorResponse.of(RawRecordErrorCode.STORAGE_FAILED));
    }
}
