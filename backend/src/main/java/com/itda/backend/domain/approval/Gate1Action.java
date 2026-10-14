package com.itda.backend.domain.approval;

/** 교사가 Gate 1 에서 누를 수 있는 버튼. 고쳐서 승인은 승인에 고친 본문을 함께 보낸 것이다. */
public enum Gate1Action {
    APPROVE,
    HOLD,
    REMOVE
}
