package com.itda.backend.domain.approval;

/** Gate 1 에서 교사가 요약 문장 카드 하나에 한 일 (#221). 승인할 때만 보낸다. */
public enum Gate1CardAction {
    /** 그대로 둔다. */
    KEEP,
    /** 글을 고친다. 근거는 원래 문장 것을 그대로 쓴다. */
    EDIT,
    /** 뺀다. */
    DELETE,
    /** 교사가 새 문장을 쓴다. 원래 문장이 없다. */
    ADD
}
