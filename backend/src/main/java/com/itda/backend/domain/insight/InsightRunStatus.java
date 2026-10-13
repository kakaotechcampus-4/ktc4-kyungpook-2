package com.itda.backend.domain.insight;

/** 인사이트 한 번 실행의 상태 (DB 스키마 §9.5). */
public enum InsightRunStatus {
    /** 집었고 아직 끝나지 않았다. 앱이 죽으면 이 상태로 남는다. */
    RUNNING,
    /** 끝났다. {@code insight_count} 가 0 이어도 정상이다 — 낼 것이 없었다는 뜻이다. */
    DONE,
    /** 실패했다. 다음 차례에 {@code revision + 1} 로 다시 집는다. */
    FAILED
}
