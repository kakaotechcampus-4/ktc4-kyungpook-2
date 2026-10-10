package com.itda.backend.service.summary;

import java.util.List;

/**
 * 워커가 집어 간 묶음과 그 안의 일지들(id 순). 승인 전 요약에 이미 들어 있던 일지도 새 일지와 함께 담긴다.
 */
public record ClaimedSummaryGroup(SummaryGroup group, List<Long> journalEntryIds) {
}
