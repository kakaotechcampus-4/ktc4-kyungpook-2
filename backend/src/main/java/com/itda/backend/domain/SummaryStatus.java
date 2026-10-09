package com.itda.backend.domain;

/**
 * 요약의 처리 상태 (DB 스키마 §8.2). 반려(REJECTED)와 대체됨(SUPERSEDED)은 두지 않는다 — 입력이 그대로면
 * 다시 만들어도 같은 글이 나오고, 승인 뒤에 온 일지는 이전 판을 밀어내지 않고 새 판으로 쌓는다.
 */
public enum SummaryStatus {
    /** 생성됨. 워커는 저장과 동시에 Gate 1 으로 넘기므로 지금은 쓰지 않는다. */
    GENERATED,
    /** 교사의 1차 검토(Gate 1)를 기다린다. 승인 전이라 새 일지가 오면 같은 판을 덮어쓴다. */
    GATE1_PENDING,
    /** 승인됨. child_context 로 넘어간다. */
    APPROVED,
    /** 교사가 보류했다. child_context 로 올라가지 않는다. */
    HOLD
}
