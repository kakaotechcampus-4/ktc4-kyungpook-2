package com.itda.backend.domain.approval;

/**
 * 사람 검토 한 번의 결정값 (DB 스키마 §9.1). 반려(REJECTED)는 두지 않는다 — 같은 입력으로 다시 만들어도 같은 글이
 * 나오므로, 교사는 고쳐서 승인하거나 보류·제거한다.
 */
public enum ApprovalDecision {
    /** AI 원문 그대로 승인했다. */
    APPROVED,
    /** 본문을 한 글자라도 고쳐서 승인했다. 인사이트는 이 값으로 고친 요약을 거른다 (§10.1 #7). */
    CORRECTED,
    /** 보류했다. */
    HOLD,
    /** 제거했다. */
    REMOVED,
    /** 보류함에서 보류를 해제했다. */
    REOPENED,
    /** 요약할 내용이 없다는 결과를 확인했다. 승인이 아니다 — 올린 글이 없다 (#244). */
    CONFIRMED
}
