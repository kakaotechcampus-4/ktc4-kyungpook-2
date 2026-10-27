package com.itda.backend.domain.summary;

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
    /** 요약할 내용이 없다는 결과(no_content)를 교사가 확인했다. 빈 글이라 child_context 로 올라가지 않는다 (#244). */
    CONFIRMED,
    /**
     * 요약할 내용이 없다는 결과를 교사가 되돌렸다. 일지는 요약 전(VALIDATED)으로 돌아가 [요약]을 다시 누르면 새 판으로
     * 요약된다. 기록으로만 남고 Gate 1 에는 보이지 않는다 (#244).
     */
    REVERTED,
    /** 교사가 보류했다. child_context 로 올라가지 않는다. 보류함에서 해제하면 Gate 1 대기로 돌아온다. */
    HOLD,
    /** 교사가 제거했다. 그 요약의 일지도 모두 삭제 표시된다. 되돌릴 수 없다. */
    REMOVED
}
