package com.itda.backend.domain;

/**
 * 서비스 역할. 로그인 뒤 회원가입(POST /api/v1/auth/signup)에서 사용자가 직접 고른다.
 * 가입 전에는 역할이 없다({@code User.role == null}).
 *
 * <p>프론트가 쓰는 소문자 표기("org"/"parent")로 바꾸는 일은 응답 DTO 가 한다.
 * API 표현을 도메인에 넣지 않는다.
 */
public enum UserRole {
    ORGANIZATION,
    PARENT
}
