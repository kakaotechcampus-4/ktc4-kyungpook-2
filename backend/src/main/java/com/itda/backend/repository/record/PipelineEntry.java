package com.itda.backend.repository.record;

import java.time.LocalDate;

import com.itda.backend.domain.record.FailureStage;
import com.itda.backend.domain.record.JournalEntryStatus;

/** 대시보드 진행 현황(#370)이 읽는 일지 한 건. 본문은 읽지 않는다. */
public record PipelineEntry(Long id, Long childId, LocalDate entryDate, JournalEntryStatus status,
        FailureStage failureStage) {
}
