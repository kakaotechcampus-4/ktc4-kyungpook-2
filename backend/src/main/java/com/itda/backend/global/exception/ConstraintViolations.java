package com.itda.backend.global.exception;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 저장 중 난 제약 위반이 어느 제약 때문인지 가린다. 같은 저장이 여러 유니크 제약에 걸릴 수 있어서
 * 제약 이름을 보지 않고 한 가지 오류로 바꾸면 다른 원인을 잘못 알린다.
 */
public final class ConstraintViolations {

    private ConstraintViolations() {
    }

    public static boolean isViolationOf(DataIntegrityViolationException exception, String constraintName) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && constraintName.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
