package com.itda.backend.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.itda.backend.global.exception.ErrorResponse;

import lombok.extern.slf4j.Slf4j;

// RawRecord 전용 예외만 처리한다. Spring MVC 표준 요청 오류와 미분류 예외(catch-all)는
// global.exception.GlobalExceptionHandler가 처리한다.
// 두 advice에 모두 해당하는 예외(MaxUploadSizeExceededException 등)는 advice 적용 순서로
// 처리기가 정해진다 — Ambiguous 오류는 같은 advice 클래스 안에서 중복 선언할 때만 난다.
@Slf4j
@RestControllerAdvice
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
