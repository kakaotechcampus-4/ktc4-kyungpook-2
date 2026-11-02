package com.itda.backend.domain.approval;

/**
 * 교사가 Gate 2 에서 누를 수 있는 버튼. Gate 1 과 달리 제거·보류 해제가 없다 — 인사이트는 재료가 그대로면
 * 다시 만들어도 같은 글이 나오고, 보류한 글은 다음 회차가 대신한다.
 */
public enum Gate2Action {
    APPROVE,
    HOLD
}
