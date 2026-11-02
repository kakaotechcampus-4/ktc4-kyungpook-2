package com.itda.backend.domain.sharing;

/**
 * 인사이트를 볼 수 있게 된 쪽 (DB 스키마 §9.4). {@code human_review} 와 같은 다형 참조라
 * {@code receiver_id} 가 가리키는 테이블이 이 값에 따라 달라진다.
 *
 * <pre>
 * ORGANIZATION → organization.id
 * GUARDIAN     → users.id
 * </pre>
 *
 * <p>Gate 2 승인이 두 값을 함께 만든다 — 보호자는 {@code GUARDIAN}, 공유 동의가 켜진 기관은
 * {@code ORGANIZATION} (#233).
 */
public enum ReceiverType {
    ORGANIZATION,
    GUARDIAN
}
