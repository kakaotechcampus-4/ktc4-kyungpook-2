package com.itda.backend.domain;

/**
 * 인사이트의 처리 상태 (DB 스키마 §9.3).
 *
 * <p>{@link SummaryStatus} 와 같은 모양이되 {@code GENERATED} 는 두지 않는다 — 워커가 저장과 동시에
 * Gate 2 로 넘기므로 쓸 자리가 없고, 쓰지 않는 값을 남겨 두면 나중에 빼기가 어렵다 (§11.4).
 *
 * <p>반려({@code REJECTED})도 두지 않는다. 재료가 그대로면 다시 만들어도 같은 글이 나오므로 반려는
 * 무한 반복이 된다 — Gate 1 과 같은 이유다 (§9.1).
 */
public enum InsightStatus {
    /** 교사의 발송 전 검토(Gate 2)를 기다린다. 워커가 저장하는 값. */
    GATE2_PENDING,
    /** 승인됐다. 허용된 수신자에게 나간다. */
    APPROVED,
    /** 교사가 보류했다. 나가지 않는다. */
    HOLD
}
