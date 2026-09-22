package com.itda.backend.domain;

/**
 * 서비스 역할. 로그인 진입 경로로 결정된다 —
 * 초대 링크(코드 보유)로 들어오면 {@link #PARENT}, 그냥 로그인하면 {@link #ORGANIZATION}.
 *
 * <p>프론트가 쓰는 소문자 표기("org"/"parent")로 바꾸는 일은 응답 DTO 가 한다.
 * API 표현을 도메인에 넣지 않는다.
 */
public enum UserRole {
    ORGANIZATION,
    PARENT
}
