package com.itda.backend.domain.record;

public enum JournalEntryStatus {
    PENDING,
    MATCHING,
    /** 매칭으로 아동이 확정돼 검증을 기다린다. DB 스키마 §6.2 초안에 없던 값을 BE가 추가했다. */
    MATCHED,
    MATCH_REVIEW,
    /** 선생님이 확인 필요 큐에서 제외했다("이 기관 아동 아님"). 여기서 끝나고 다음 단계로 가지 않는다. BE가 추가했다. */
    EXCLUDED,
    CONSENT_BLOCKED,
    VALIDATING,
    /** 검증을 통과해(PASS·REVIEW) 요약을 기다린다. REVIEW 의 문제 문장은 요약 뒤 Gate 1 에서 표시한다. BE가 추가했다. */
    VALIDATED,
    /** 검증에서 막혔다(BLOCK). 요약으로 넘기지 않고 수정 요청 큐로 간다. 호출 실패(SYSTEM_ERROR)와 구분한다. BE가 추가했다. */
    VALIDATION_BLOCKED,
    /**
     * 수정 요청 큐에서 선생님이 "수정한 원본 다시 올리기"를 눌렀다. 원본은 고치지 않고(append-only)
     * 고친 내용을 새 파일로 올리므로, 이 일지는 대체될 예정이라 여기서 끝난다. BE가 추가했다(#122).
     */
    REUPLOAD_REQUESTED,
    /** 수정 요청 큐에서 선생님이 "이 기록 보류"를 눌렀다. 다음 단계로 가지 않는다. BE가 추가했다(#122). */
    VALIDATION_HELD,
    SUMMARIZING,
    GATE1_PENDING,
    /** 이 일지가 든 요약을 교사가 Gate 1 에서 보류했다. 워커가 다시 집어 가지 않고, 보류 해제하면 GATE1_PENDING 으로 돌아온다. BE가 추가했다(#178). */
    GATE1_HELD,
    /** 이 일지가 든 요약이 Gate 1 에서 승인됐다. 요약에 안 들어간 일지(uncovered)도 함께 끝난다. */
    COMPLETED,
    /**
     * 요약에 근거가 남은 문장이 하나도 없어(AI 503 {@code no_grounded_claims}) 재시도 뒤에도 요약하지 못했다. 주로 모델이
     * 인용을 원문과 다르게 옮긴 경우다. 워커가 다시 집지 않는다. 묶음 카드에는 넣지 않고, #182 다음 이슈에서 1차 검토에
     * "근거 없는 요약"으로 띄워 교사가 확인하거나 되돌리게 한다. BE가 추가했다(#180).
     */
    SUMMARY_FAILED,
    /**
     * 코드 버그나 AI 계약 위반으로 검증·요약이 멈췄다. 다시 돌려도 같으므로 워커가 집지 않고, 관리자가 고친 뒤
     * 원인({@code failure_reason})별로 골라 되돌린다. 어느 단계인지는 {@code failure_stage} 가 가진다. BE가 추가했다(#180).
     */
    SYSTEM_ERROR,
    /** 매칭 호출이 실패했다. 확인 필요 큐에서 사람이 아이를 고른다. #180 전에는 검증·요약 실패도 이 값이었다. */
    FAILED
}
