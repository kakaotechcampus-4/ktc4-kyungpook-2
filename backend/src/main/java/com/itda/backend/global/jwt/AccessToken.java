package com.itda.backend.global.jwt;

import java.time.Instant;

/**
 * 서명을 확인한 출입증의 내용. 인증용 {@link JwtProvider#parse} 는 만료도 확인하고,
 * 로그아웃용 {@link JwtProvider#parseForLogout} 은 만료됐어도 최대 로그인 시간이 남아 있으면 읽는다.
 *
 * @param subject   내부 userId
 * @param sessionId 로그인 번호({@code sid}). 로그인할 때 정해져 연장해도 그대로다. 로그아웃은 이 번호를 무효로 한다.
 *                  자동 연장이 생기기 전에 발급된 출입증에는 없다(null) — 그런 출입증은 연장하지 않는다
 * @param loginAt   처음 로그인한 시각({@code auth_time}). 연장해도 그대로라 최대 로그인 시간을 여기서 잰다
 * @param expiresAt 이 출입증의 만료 시각
 */
public record AccessToken(String subject, String sessionId, Instant loginAt, Instant expiresAt) {
}
