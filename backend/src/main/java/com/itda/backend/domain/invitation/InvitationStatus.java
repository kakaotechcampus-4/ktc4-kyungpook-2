package com.itda.backend.domain.invitation;

/**
 * 초대 코드 상태. 만료는 상태가 아니다 — {@code PENDING} 이어도 {@code expiresAt} 이 지났으면 쓸 수 없다.
 * 스케줄러가 상태를 바꿔 주지 않아도 언제나 정확하도록 만료는 시각으로만 판단한다 (DB 스키마 §5.3).
 */
public enum InvitationStatus {
    PENDING,
    ACCEPTED,
    CANCELLED
}
