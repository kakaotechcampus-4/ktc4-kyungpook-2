package com.itda.backend.repository.summary;

import java.time.LocalDate;

import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.domain.summary.SummaryStatus;

/**
 * Gate 1 목록의 요약 한 편을 가리키는 칸만. 본문 · 문장 카드 · AI 원문 같은 TEXT 칸은 읽지 않는다 — 대시보드가 10초마다
 * 묻는 보드에는 이 칸만 쓴다. 동의 거르기 · "곧 바뀌는 요약" 판정도 이 칸만 본다.
 */
public record Gate1Key(Long id, Long childId, LocalDate entryDate, Integer revision, SummaryStatus status,
        boolean noContent) {

    public static Gate1Key of(SummaryResult summary) {
        return new Gate1Key(summary.getId(), summary.getChildId(), summary.getEntryDate(), summary.getRevision(),
                summary.getStatus(), summary.isNoContent());
    }
}
