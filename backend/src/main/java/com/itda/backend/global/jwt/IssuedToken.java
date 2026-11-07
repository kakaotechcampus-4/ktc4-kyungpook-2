package com.itda.backend.global.jwt;

import java.time.Duration;

/**
 * 연장으로 새로 만든 출입증과 그 쿠키에 걸 만료.
 *
 * @param token  새 출입증
 * @param maxAge 지금부터 이 출입증의 만료까지. 최대 로그인 시간에 걸리면 유효시간보다 짧다
 */
public record IssuedToken(String token, Duration maxAge) {
}
