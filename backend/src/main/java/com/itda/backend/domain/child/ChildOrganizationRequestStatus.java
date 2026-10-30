package com.itda.backend.domain.child;

/**
 * 보호자가 낸 기관 연결 요청의 상태. 처리된 요청도 지우지 않고 상태만 바꾼다 — 누가 언제 요청했고 기관이 어떻게
 * 답했는지가 남아야 하고, 거절·취소된 뒤 다시 요청하면 새 행을 만든다 (DB 스키마 §5.6).
 */
public enum ChildOrganizationRequestStatus {
    /** 기관의 답을 기다린다 */
    PENDING,
    /** 기관이 수락해 아이가 명부에 올랐다 */
    ACCEPTED,
    /** 기관이 거절했다 */
    REJECTED,
    /** 요청한 보호자의 아이 연결이 끊겨 요청도 거뒀다 */
    CANCELLED
}
