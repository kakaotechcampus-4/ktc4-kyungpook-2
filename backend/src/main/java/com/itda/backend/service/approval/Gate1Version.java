package com.itda.backend.service.approval;

import com.itda.backend.domain.summary.SummaryResult;

/**
 * 교사가 Gate 1 목록에서 본 요약의 판 표시. 결정할 때 그대로 돌려받아, 그 사이 워커가 같은 요약을 새 일지까지 묶어
 * 덮어썼으면 결정을 받지 않는다 — 덮어써도 id 와 판 번호는 그대로라 그것만으로는 모른다.
 *
 * <p>요약 행이 바뀔 때마다 갱신되는 {@code updated_at} 을 쓴다. 화면은 이 값을 해석하지 않고 그대로 돌려보낸다.
 */
public final class Gate1Version {

    private Gate1Version() {
    }

    public static String of(SummaryResult summary) {
        return summary.getUpdatedAt().toString();
    }
}
