package com.itda.backend.service.approval;

import java.util.Collection;
import java.util.stream.Collectors;

import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.summary.SummaryResult;

/**
 * 교사가 Gate 1 목록에서 본 요약의 판 표시. 결정할 때 그대로 돌려받아, 그 사이 워커가 같은 요약을 새 일지까지 묶어
 * 덮어썼으면 결정을 받지 않는다 — 덮어써도 id 와 판 번호는 그대로라 그것만으로는 모른다.
 *
 * <p>요약 행의 {@code updated_at} 과 그 요약에 든 일지 id 를 합친다. AI 가 같은 글을 돌려주면 요약 행이 바뀌지 않아
 * 수정 시각도 그대로이므로, 일지가 늘거나 빠진 것은 일지 목록으로 잡는다. 화면은 이 값을 해석하지 않고 그대로 돌려보낸다.
 *
 * @param entries 그 요약을 가리키는 삭제되지 않은 일지
 */
public final class Gate1Version {

    private Gate1Version() {
    }

    public static String of(SummaryResult summary, Collection<JournalEntry> entries) {
        String entryIds = entries.stream()
                .map(JournalEntry::getId)
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        return summary.getUpdatedAt() + "/" + entryIds;
    }
}
