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
 * <p>⬜ <b>{@code ORGANIZATION} 행은 아직 만들어지지 않는다.</b> 10/17 회의 결론이
 * {@code 담당자 승인 → 보호자 → 보호자가 기관별 승인} 이라, Gate 2 승인은 {@code GUARDIAN} 만 만들고
 * 기관 권한은 보호자의 기관별 승인 API(미구현)가 만든다. 그래도 값은 지금 둔다 — 나중에 넣으면
 * {@code ddl-auto: update} 가 CHECK 제약을 고쳐주지 않아 §11.4 수동 조치 대상이 된다.
 */
public enum ReceiverType {
    ORGANIZATION,
    GUARDIAN
}
