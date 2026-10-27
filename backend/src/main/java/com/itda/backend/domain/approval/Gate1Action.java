package com.itda.backend.domain.approval;

/**
 * 교사가 Gate 1 에서 누를 수 있는 버튼. 고쳐서 승인은 승인에 고친 본문을 함께 보낸 것이다. 요약할 내용 없음(#244)은
 * 확인(CONFIRM)과 요약 되돌리기(REVERT)만 받고, 다른 요약은 그 둘을 받지 않는다.
 */
public enum Gate1Action {
    APPROVE,
    HOLD,
    REMOVE,
    CONFIRM,
    REVERT
}
